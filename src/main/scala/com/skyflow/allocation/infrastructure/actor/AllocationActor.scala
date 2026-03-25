package com.skyflow.allocation.infrastructure.actor

import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.util.Timeout
import org.slf4j.LoggerFactory
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Success, Failure}
import java.time.LocalDateTime

import com.skyflow.allocation.domain.model._
import com.skyflow.allocation.domain.service.{AllocationDomainService, DefaultAllocationDomainService}
import com.skyflow.allocation.application.command._
import com.skyflow.flight.application.command.{RegistryCommand, GetAllFlights, FlightsRetrieved, RegistryResponse}
import com.skyflow.shared.infrastructure.observability.{CorrelationId, CorrelationContext, Metrics}

/**
 * AllocationActor — Infrastructure adapter for allocation coordination.
 *
 * Delegates core allocation logic to AllocationDomainService (pure domain service).
 */
object AllocationActor {

  private val logger = LoggerFactory.getLogger(getClass)

  implicit val timeout: Timeout = 10.seconds

  private case class State(
    activeRuns: Map[String, AllocationRun] = Map.empty,
    completedResults: Map[String, AllocationResult] = Map.empty
  ) {
    def startRun(allocationId: String, run: AllocationRun): State =
      copy(activeRuns = activeRuns + (allocationId -> run))

    def completeRun(allocationId: String, result: AllocationResult): State =
      copy(
        activeRuns = activeRuns - allocationId,
        completedResults = completedResults + (allocationId -> result)
      )

    def failRun(allocationId: String): State =
      copy(activeRuns = activeRuns - allocationId)

    def getResult(allocationId: String): Option[AllocationResult] =
      completedResults.get(allocationId)

    def getProgress(allocationId: String): Option[Double] =
      activeRuns.get(allocationId).map(_.progress)
  }

  private val domainService: AllocationDomainService = new DefaultAllocationDomainService()

  def apply(registry: ActorRef[RegistryCommand]): Behavior[AllocationCommand] = {
    Behaviors.setup { context =>
      logger.info("AllocationActor started at {}", context.self.path)
      active(State(), registry, context)
    }
  }

  private def active(
    state: State,
    registry: ActorRef[RegistryCommand],
    context: ActorContext[AllocationCommand]
  ): Behavior[AllocationCommand] = {
    Behaviors.receive[AllocationCommand] { (ctx, msg) =>
      msg match {
        case StartAllocation(request, replyTo) =>
          handleStartAllocation(state, registry, context, request, replyTo)

        case GetAllocationStatus(allocationId, replyTo) =>
          handleGetAllocationStatus(state, allocationId, replyTo)

        case GetAllocationResult(allocationId, replyTo) =>
          handleGetAllocationResult(state, allocationId, replyTo)

        case WrappedAllocationComplete(allocationId, result, replyTo) =>
          handleAllocationComplete(state, registry, context, allocationId, result)

        case WrappedAllocationFailed(allocationId, reason, replyTo) =>
          handleAllocationFailed(state, registry, context, allocationId, reason)
      }
    }.receiveSignal {
      case (_, PostStop) =>
        logger.info("AllocationActor stopped")
        Behaviors.same
    }
  }

  private def handleStartAllocation(
    state: State,
    registry: ActorRef[RegistryCommand],
    context: ActorContext[AllocationCommand],
    request: AllocationRequest,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    logger.info("Starting allocation run: {} with runId: {}", request.allocationId, request.runId)

    val correlationContext = CorrelationContext(
      correlationId = CorrelationId.generate(),
      runId = Some(request.runId)
    )
    CorrelationId.setContext(correlationContext)

    val tracker = Metrics.track(s"allocation-${request.allocationId}")(logger)

    // Reply immediately that allocation has started
    replyTo ! AllocationStarted(request.allocationId, request.runId)

    val run = AllocationRun(request, LocalDateTime.now())

    // Process allocation asynchronously
    context.pipeToSelf(
      processAllocation(registry, request, context, tracker)
    ) {
      case Success(result) =>
        tracker.complete()
        WrappedAllocationComplete(request.allocationId, result, replyTo)

      case Failure(exception) =>
        tracker.complete()
        logger.error("Allocation failed for {}: {}", request.allocationId, exception.getMessage)
        WrappedAllocationFailed(request.allocationId, exception.getMessage, replyTo)
    }

    active(state.startRun(request.allocationId, run), registry, context)
  }

  /**
   * Process allocation — delegates to the pure AllocationDomainService.
   */
  private def processAllocation(
    registry: ActorRef[RegistryCommand],
    request: AllocationRequest,
    context: ActorContext[AllocationCommand],
    tracker: Metrics.PerformanceTracker
  ): Future[AllocationResult] = {
    import context.executionContext
    import akka.actor.typed.scaladsl.AskPattern._

    logger.info("Processing allocation for {} O&D pairs", request.odPairs.size)
    tracker.checkpoint("start-processing")

    // Query registry for available flights
    implicit val scheduler = context.system.scheduler
    val flightsFuture: Future[RegistryResponse] =
      registry.ask[RegistryResponse](replyTo => GetAllFlights(replyTo))(timeout, scheduler)

    flightsFuture.map {
      case FlightsRetrieved(flights) =>
        import com.skyflow.allocation.domain.service.FlightCapacity
        // Map Flight domain objects to FlightCapacity (anti-corruption layer)
        val flightCapacities = flights.map { f =>
          FlightCapacity(
            flightId = f.id.value,
            originCode = f.route.origin.code.value,
            destinationCode = f.route.destination.code.value,
            availableSeats = f.availableSeats
          )
        }

        tracker.checkpoint("flights-loaded")

        // Delegate to pure domain service
        val (allocations, unallocated) = domainService.allocate(
          request.odPairs, flightCapacities, request.priority
        )

        tracker.checkpoint("allocations-complete")

        AllocationResult(
          allocationId = request.allocationId,
          runId = request.runId,
          allocations = allocations,
          unallocated = unallocated,
          processingTimeMs = tracker.elapsed,
          completedAt = LocalDateTime.now()
        )

      case _ =>
        // No flights available — everything goes to unallocated
        AllocationResult(
          allocationId = request.allocationId,
          runId = request.runId,
          allocations = Nil,
          unallocated = request.odPairs,
          processingTimeMs = tracker.elapsed,
          completedAt = LocalDateTime.now()
        )
    }
  }

  private def handleGetAllocationStatus(
    state: State,
    allocationId: String,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    state.getProgress(allocationId) match {
      case Some(progress) =>
        replyTo ! AllocationInProgress(allocationId, progress)
      case None =>
        state.getResult(allocationId) match {
          case Some(result) => replyTo ! AllocationComplete(result)
          case None         => replyTo ! AllocationNotFound(allocationId)
        }
    }
    Behaviors.same
  }

  private def handleGetAllocationResult(
    state: State,
    allocationId: String,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    state.getResult(allocationId) match {
      case Some(result) => replyTo ! AllocationComplete(result)
      case None =>
        if (state.activeRuns.contains(allocationId))
          replyTo ! AllocationInProgress(allocationId, state.getProgress(allocationId).getOrElse(0.0))
        else
          replyTo ! AllocationNotFound(allocationId)
    }
    Behaviors.same
  }

  private def handleAllocationComplete(
    state: State,
    registry: ActorRef[RegistryCommand],
    context: ActorContext[AllocationCommand],
    allocationId: String,
    result: AllocationResult
  ): Behavior[AllocationCommand] = {
    logger.info("Allocation {} completed successfully", allocationId)
    active(state.completeRun(allocationId, result), registry, context)
  }

  private def handleAllocationFailed(
    state: State,
    registry: ActorRef[RegistryCommand],
    context: ActorContext[AllocationCommand],
    allocationId: String,
    reason: String
  ): Behavior[AllocationCommand] = {
    logger.error("Allocation {} failed: {}", allocationId, reason)
    active(state.failRun(allocationId), registry, context)
  }
}

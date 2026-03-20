package com.akka.learning.actors

import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.util.Timeout
import org.slf4j.LoggerFactory
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Success, Failure}
import java.time.LocalDateTime

import com.akka.learning.models.domain._
import com.akka.learning.models.commands._
import com.akka.learning.utils.{CorrelationId, Metrics}
import com.akka.learning.models.infrastructure.CorrelationContext

/**
 * AllocationActor - Coordinator for batch seat allocation runs
 * 
 * Demonstrates:
 * - Coordination across multiple actors
 * - Ask pattern with timeout
 * - Complex business logic orchestration
 * - Performance tracking
 * - Correlation context propagation
 */
object AllocationActor {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  implicit val timeout: Timeout = 10.seconds

  /**
   * Internal state tracking allocation runs
   */
  private case class State(
    activeRuns: Map[String, AllocationRun] = Map.empty,
    completedResults: Map[String, AllocationResult] = Map.empty
  ) {
    def startRun(allocationId: String, run: AllocationRun): State = {
      copy(activeRuns = activeRuns + (allocationId -> run))
    }
    
    def completeRun(allocationId: String, result: AllocationResult): State = {
      copy(
        activeRuns = activeRuns - allocationId,
        completedResults = completedResults + (allocationId -> result)
      )
    }
    
    def failRun(allocationId: String): State = {
      copy(activeRuns = activeRuns - allocationId)
    }
    
    def getResult(allocationId: String): Option[AllocationResult] = {
      completedResults.get(allocationId)
    }
    
    def getProgress(allocationId: String): Option[Double] = {
      activeRuns.get(allocationId).map(_.progress)
    }
  }

  /**
   * Tracks an active allocation run
   */
  private case class AllocationRun(
    request: AllocationRequest,
    startTime: LocalDateTime,
    progress: Double = 0.0
  )

  /**
   * Create AllocationActor behavior
   */
  def apply(registry: ActorRef[RegistryCommand]): Behavior[AllocationCommand] = {
    Behaviors.setup { context =>
      logger.info("AllocationActor started at {}", context.self.path)
      active(State(), registry, context)
    }
  }

  /**
   * Active behavior with state
   */
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
          handleAllocationComplete(state, registry, context, allocationId, result, replyTo)
        
        case WrappedAllocationFailed(allocationId, reason, replyTo) =>
          handleAllocationFailed(state, registry, context, allocationId, reason, replyTo)
      }
    }.receiveSignal {
      case (_, PostStop) =>
        logger.info("AllocationActor stopped")
        Behaviors.same
    }
  }

  /**
   * Start a new allocation run
   */
  private def handleStartAllocation(
    state: State,
    registry: ActorRef[RegistryCommand],
    context: ActorContext[AllocationCommand],
    request: AllocationRequest,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    
    logger.info("Starting allocation run: {} with runId: {}", request.allocationId, request.runId)
    
    // Set up correlation context
    val correlationContext = CorrelationContext(
      correlationId = CorrelationId.generate(),
      runId = Some(request.runId)
    )
    CorrelationId.setContext(correlationContext)
    
    // Start performance tracking
    val tracker = Metrics.track(s"allocation-${request.allocationId}")(logger)
    
    // Reply immediately that allocation has started
    replyTo ! AllocationStarted(request.allocationId, request.runId)
    
    // Create run state
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
   * Process allocation logic
   */
  private def processAllocation(
    registry: ActorRef[RegistryCommand],
    request: AllocationRequest,
    context: ActorContext[AllocationCommand],
    tracker: Metrics.PerformanceTracker
  ): Future[AllocationResult] = {
    
    import context.executionContext
    
    logger.info("Processing allocation for {} O&D pairs", request.odPairs.size)
    tracker.checkpoint("start-processing")
    
    // Simplified allocation logic for Phase 2
    // In Phase 4 (Streams), this will be replaced with sophisticated stream processing
    
    val allocations = scala.collection.mutable.ListBuffer[Allocation]()
    val unallocated = scala.collection.mutable.ListBuffer[(ODPair, Int)]()
    
    // Process each O&D pair sequentially (will be parallel in Phase 4)
    request.odPairs.foreach { case (odPair, demand) =>
      logger.debug("Processing O&D pair: {} with demand: {}", odPair.toKey, demand)
      
      // In a real implementation, we would:
      // 1. Query FlightRegistry for available flights on this route
      // 2. Sort by best match (direct flights, capacity, schedule)
      // 3. Allocate seats across one or more flights
      // 4. Handle both allocated and unallocated demand
      
      // For Phase 2, simplified logic:
      // Assume we can't allocate (will be implemented properly in Phase 4)
      unallocated += ((odPair, demand))
    }
    
    tracker.checkpoint("allocations-complete")
    
    val result = AllocationResult(
      allocationId = request.allocationId,
      runId = request.runId,
      allocations = allocations.toList,
      unallocated = unallocated.toList,
      processingTimeMs = tracker.elapsed,
      completedAt = LocalDateTime.now()
    )
    
    logger.info("Allocation complete. Allocated: {}, Unallocated: {}, Time: {}ms",
      allocations.size, unallocated.size, result.processingTimeMs)
    
    Future.successful(result)
  }

  /**
   * Get allocation status
   */
  private def handleGetAllocationStatus(
    state: State,
    allocationId: String,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    
    state.getProgress(allocationId) match {
      case Some(progress) =>
        logger.debug("Allocation {} is in progress: {}%", allocationId, (progress * 100).toInt)
        replyTo ! AllocationInProgress(allocationId, progress)
      
      case None =>
        state.getResult(allocationId) match {
          case Some(result) =>
            logger.debug("Allocation {} is complete", allocationId)
            replyTo ! AllocationComplete(result)
          
          case None =>
            logger.warn("Allocation {} not found", allocationId)
          replyTo ! AllocationNotFound(allocationId)
        }
    }
    
    Behaviors.same
  }

  /**
   * Get allocation result
   */
  private def handleGetAllocationResult(
    state: State,
    allocationId: String,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    
    state.getResult(allocationId) match {
      case Some(result) =>
        logger.debug("Returning result for allocation {}", allocationId)
        replyTo ! AllocationComplete(result)
      
      case None =>
        if (state.activeRuns.contains(allocationId)) {
          logger.debug("Allocation {} still in progress", allocationId)
          replyTo ! AllocationInProgress(allocationId, state.getProgress(allocationId).getOrElse(0.0))
        } else {
          logger.warn("Allocation {} not found", allocationId)
          replyTo ! AllocationNotFound(allocationId)
        }
    }
    
    Behaviors.same
  }

  /**
   * Handle successful allocation completion
   */
  private def handleAllocationComplete(
    state: State,
    registry: ActorRef[RegistryCommand],
    context: ActorContext[AllocationCommand],
    allocationId: String,
    result: AllocationResult,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    
    logger.info("Allocation {} completed successfully", allocationId)
    // Note: replyTo was already notified when allocation started
    // Future enhancements could add progress notifications
    
    active(state.completeRun(allocationId, result), registry, context)
  }

  /**
   * Handle allocation failure
   */
  private def handleAllocationFailed(
    state: State,
    registry: ActorRef[RegistryCommand],
    context: ActorContext[AllocationCommand],
    allocationId: String,
    reason: String,
    replyTo: ActorRef[AllocationResponse]
  ): Behavior[AllocationCommand] = {
    
    logger.error("Allocation {} failed: {}", allocationId, reason)
    // Future enhancement: could notify replyTo about failure
    
    active(state.failRun(allocationId), registry, context)
  }
}

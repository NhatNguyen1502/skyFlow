package com.skyflow.flight.infrastructure.actor

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.scaladsl.AskPattern._
import akka.persistence.query.PersistenceQuery
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.stream.{Materializer, SystemMaterializer}
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.skyflow.flight.domain.model.Flight
import com.skyflow.flight.application.command._

/**
 * FlightRegistryActor — Supervisor managing multiple FlightActor instances.
 *
 * Infrastructure adapter that handles:
 * - Dynamic child actor spawning
 * - Two-phase journal recovery on startup
 * - In-memory flight cache
 */
object FlightRegistryActor {

  private val logger = LoggerFactory.getLogger(getClass)

  private case class State(
    flightActors: Map[String, ActorRef[FlightCommand]] = Map.empty,
    flightData: Map[String, Flight] = Map.empty
  ) {
    def addFlight(flightId: String, actorRef: ActorRef[FlightCommand], flight: Flight): State =
      copy(
        flightActors = flightActors + (flightId -> actorRef),
        flightData = flightData + (flightId -> flight)
      )

    def getActor(flightId: String): Option[ActorRef[FlightCommand]] =
      flightActors.get(flightId)

    def getAllFlights: List[Flight] = flightData.values.toList
  }

  def apply(): Behavior[RegistryCommand] = {
    Behaviors.withStash(capacity = 200) { stash =>
      Behaviors.setup { context =>
        implicit val ec: ExecutionContext = context.executionContext
        implicit val mat: Materializer = SystemMaterializer(context.system).materializer

        logger.info("FlightRegistry starting — recovering flights from journal...")

        val readJournal = PersistenceQuery(context.system.classicSystem)
          .readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

        val idsFuture: Future[List[String]] =
          readJournal
            .currentPersistenceIds()
            .filter(_.startsWith("flight-"))
            .runWith(Sink.seq)
            .map(_.toList)

        context.pipeToSelf(idsFuture) {
          case Success(ids) => WrappedRecoveryPersistenceIds(ids)
          case Failure(ex)  => WrappedRecoveryFailed(ex.getMessage)
        }

        recovering(stash)
      }
    }
  }

  private def recovering(stash: StashBuffer[RegistryCommand]): Behavior[RegistryCommand] = {
    Behaviors.receive { (context, command) =>
      command match {
        case WrappedRecoveryPersistenceIds(ids) =>
          if (ids.isEmpty) {
            logger.info("FlightRegistry: no previous flights found. Starting fresh.")
            stash.unstashAll(active(State()))
          } else {
            logger.info("Found {} flight(s) in journal, spawning actors...", ids.size)
            implicit val timeout: Timeout = 5.seconds
            implicit val scheduler = context.system.scheduler
            implicit val ec = context.executionContext

            val actors: List[(String, ActorRef[FlightCommand])] = ids.map { pid =>
              val flightId = pid.stripPrefix("flight-")
              val actor = context.spawn(
                Behaviors.supervise(FlightActor(flightId))
                  .onFailure[Exception](
                    SupervisorStrategy.restart.withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
                  ),
                s"flight-$flightId"
              )
              (flightId, actor)
            }

            val statesFuture: Future[List[(String, ActorRef[FlightCommand], Flight)]] =
              Future.sequence(actors.map { case (flightId, actor) =>
                actor.ask[FlightResponse](replyTo => GetFlightInfo(flightId, replyTo))
                  .map {
                    case FlightInfo(flight) =>
                      logger.info("Recovered flight {} ({})", flightId, flight.flightNumber.value)
                      Some((flightId, actor, flight))
                    case other =>
                      logger.warn("Unexpected response while recovering flight {}: {}", flightId, other)
                      None
                  }
                  .recover { case ex =>
                    logger.error("Failed to recover flight {}: {}", flightId, ex.getMessage)
                    None
                  }
              }).map(_.flatten)

            context.pipeToSelf(statesFuture) {
              case Success(flights) => WrappedRecoveryComplete(flights)
              case Failure(ex)      => WrappedRecoveryFailed(ex.getMessage)
            }

            Behaviors.same
          }

        case WrappedRecoveryComplete(recovered) =>
          val initialState = recovered.foldLeft(State()) { case (s, (id, actor, flight)) =>
            s.addFlight(id, actor, flight)
          }
          logger.info("FlightRegistry recovery complete {} flight(s) loaded.", initialState.flightData.size)
          stash.unstashAll(active(initialState))

        case WrappedRecoveryFailed(reason) =>
          logger.error("FlightRegistry recovery failed: {}. Starting with empty state.", reason)
          stash.unstashAll(active(State()))

        case other =>
          stash.stash(other)
          Behaviors.same
      }
    }
  }

  private def active(state: State): Behavior[RegistryCommand] = {
    Behaviors.receive { (context, command) =>
      command match {
        case RegisterFlight(flight, replyTo) =>
          handleRegisterFlight(context, state, flight, replyTo)

        case GetAllFlights(replyTo) =>
          handleGetAllFlights(state, replyTo)

        case GetFlight(flightId, replyTo) =>
          handleGetFlight(context, state, flightId, replyTo)

        case WrappedRegisterFlightSuccess(flightId, flight, replyTo) =>
          logger.info("Flight {} registered successfully", flightId)
          replyTo ! RegistryFlightCreated(flight)
          Behaviors.same

        case WrappedRegisterFlightFailure(flightId, reason, replyTo) =>
          logger.error("Flight {} registration failed: {}", flightId, reason)
          replyTo ! RegistryOperationFailed(flightId, reason)
          Behaviors.same

        case WrappedGetFlightSuccess(flightId, flight, replyTo) =>
          logger.debug("Retrieved flight {} from actor", flightId)
          val updatedState = state.copy(flightData = state.flightData + (flightId -> flight))
          replyTo ! FlightDetails(flight)
          active(updatedState)

        case WrappedGetFlightNotFound(flightId, replyTo) =>
          logger.warn("Flight {} not found in actor", flightId)
          replyTo ! RegistryFlightNotFound(flightId)
          Behaviors.same

        case WrappedRecoveryPersistenceIds(_) | WrappedRecoveryComplete(_) | WrappedRecoveryFailed(_) =>
          logger.warn("Received late recovery message in active state — ignoring")
          Behaviors.same
      }
    }
  }

  private def handleRegisterFlight(
    context: ActorContext[RegistryCommand],
    state: State,
    flight: Flight,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    state.getActor(flight.id.value) match {
      case Some(_) =>
        logger.warn("Flight {} already registered", flight.id.value)
        replyTo ! RegistryOperationFailed(flight.id.value, "Flight already exists")
        Behaviors.same

      case None =>
        logger.info("Registering new flight: {}", flight.id.value)
        val flightActor = context.spawn(
          Behaviors.supervise(FlightActor(flight.id.value))
            .onFailure[Exception](
              SupervisorStrategy.restart.withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
            ),
          s"flight-${flight.id.value}"
        )

        val responseAdapter: ActorRef[FlightResponse] = context.messageAdapter {
          case FlightCreatedResponse(fid) =>
            WrappedRegisterFlightSuccess(fid, flight, replyTo)
          case FlightOperationFailed(_, reason) =>
            WrappedRegisterFlightFailure(flight.id.value, reason, replyTo)
          case other =>
            WrappedRegisterFlightFailure(flight.id.value, s"Unexpected response: $other", replyTo)
        }

        flightActor ! CreateFlight(flight, responseAdapter)
        val newState = state.addFlight(flight.id.value, flightActor, flight)
        active(newState)
    }
  }

  private def handleGetAllFlights(
    state: State,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    val allFlights = state.getAllFlights
    logger.info("GET ALL FLIGHTS — count: {}", allFlights.size)
    replyTo ! FlightsRetrieved(allFlights)
    Behaviors.same
  }

  private def handleGetFlight(
    context: ActorContext[RegistryCommand],
    state: State,
    flightId: String,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    state.flightData.get(flightId) match {
      case Some(flight) =>
        logger.debug("Found flight in cache: {}", flightId)
        replyTo ! FlightDetails(flight)
        Behaviors.same

      case None =>
        state.getActor(flightId) match {
          case Some(flightActor) =>
            logger.debug("Flight not in cache, querying FlightActor: {}", flightId)
            val responseAdapter: ActorRef[FlightResponse] = context.messageAdapter {
              case FlightInfo(flight) =>
                WrappedGetFlightSuccess(flightId, flight, replyTo)
              case FlightNotFound(_) =>
                WrappedGetFlightNotFound(flightId, replyTo)
              case _ =>
                WrappedGetFlightNotFound(flightId, replyTo)
            }
            flightActor ! GetFlightInfo(flightId, responseAdapter)
            Behaviors.same

          case None =>
            logger.warn("Flight not found: {}", flightId)
            replyTo ! RegistryFlightNotFound(flightId)
            Behaviors.same
        }
    }
  }
}

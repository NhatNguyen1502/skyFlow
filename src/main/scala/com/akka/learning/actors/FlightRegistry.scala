package com.akka.learning.actors

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

import com.akka.learning.models.domain.Flight
import com.akka.learning.models.commands._

/**
 * FlightRegistry - Supervisor Actor managing multiple FlightActor instances
 * 
 * Demonstrates:
 * - Supervisor pattern with dynamic child spawning
 * - Actor routing and lifecycle management
 * - Ask pattern for child communication
 * - State management in stateful actors
 */
object FlightRegistry {
  
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Internal state tracking all registered flights
   */
  private case class State(
    flightActors: Map[String, ActorRef[FlightCommand]] = Map.empty,
    flightData: Map[String, Flight] = Map.empty
  ) {
    def addFlight(flightId: String, actorRef: ActorRef[FlightCommand], flight: Flight): State = {
      copy(
        flightActors = flightActors + (flightId -> actorRef),
        flightData = flightData + (flightId -> flight)
      )
    }
    
    def getActor(flightId: String): Option[ActorRef[FlightCommand]] = {
      flightActors.get(flightId)
    }
    
    def getAllFlights: List[Flight] = flightData.values.toList
    
    def findFlightsByRoute(origin: String, destination: String): List[Flight] = {
      flightData.values.filter { flight =>
        flight.route.origin.code == origin && flight.route.destination.code == destination
      }.toList
    }
  }

  /**
   * Create FlightRegistry behavior.
   * On startup, queries the Read Journal for all persisted flight IDs,
   * spawns a FlightActor for each (which auto-recovers from the event journal),
   * then rebuilds the in-memory cache before accepting commands.
   */
  /**
   * Create FlightRegistry behavior.
   *
   * Recovery is split into two safe steps:
   *  1. Query journal for persistence IDs inside a Future (no ActorContext access).
   *  2. Spawn FlightActors + ask their state inside Behaviors.receive (safe actor thread).
   */
  def apply(): Behavior[RegistryCommand] = {
    Behaviors.withStash(capacity = 200) { stash =>
      Behaviors.setup { context =>
        implicit val ec: ExecutionContext = context.executionContext
        implicit val mat: Materializer   = SystemMaterializer(context.system).materializer

        logger.info("FlightRegistry starting — recovering flights from journal...")

        val readJournal = PersistenceQuery(context.system.classicSystem)
          .readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

        // Step 1: Only fetch IDs — no actor context calls inside this Future
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

  /**
   * Recovering behavior — stashes all external commands until journal recovery finishes.
   *
   * Uses Behaviors.receive to get ActorContext, which is needed for:
   *  - context.spawn (safe — runs on actor thread)
   *  - context.pipeToSelf (safe — called from actor thread)
   */
  private def recovering(stash: StashBuffer[RegistryCommand]): Behavior[RegistryCommand] = {
    Behaviors.receive { (context, command) =>
      command match {

        // Step 2: IDs arrived — NOW safe to spawn actors (we are on the actor thread)
        case WrappedRecoveryPersistenceIds(ids) =>
          if (ids.isEmpty) {
            logger.info("FlightRegistry: no previous flights found. Starting fresh.")
            stash.unstashAll(active(State()))
          } else {
            logger.info("Found {} flight(s) in journal, spawning actors...", ids.size)
            implicit val timeout: Timeout = 5.seconds
            implicit val scheduler        = context.system.scheduler
            implicit val ec               = context.executionContext

            // Spawn each FlightActor — safe, we are inside Behaviors.receive
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

            // Ask each actor for its recovered state (actors are thread-safe to ask from anywhere)
            val statesFuture: Future[List[(String, ActorRef[FlightCommand], Flight)]] =
              Future.sequence(actors.map { case (flightId, actor) =>
                actor.ask[FlightResponse](replyTo => GetFlightInfo(flightId, replyTo))
                  .map {
                    case FlightInfo(flight) =>
                      logger.info("Recovered flight {} ({})", flightId, flight.flightNumber)
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

            Behaviors.same // still recovering — wait for WrappedRecoveryComplete
          }

        case WrappedRecoveryComplete(recovered) =>
          val initialState = recovered.foldLeft(State()) { case (s, (id, actor, flight)) =>
            s.addFlight(id, actor, flight)
          }
          logger.info("FlightRegistry recovery complete — {} flight(s) loaded.", initialState.flightData.size)
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

  /**
   * Active behavior with state
   */
  private def active(state: State): Behavior[RegistryCommand] = {
    Behaviors.receive { (context, command) =>
      command match {
        case RegisterFlight(flight, replyTo) =>
          handleRegisterFlight(context, state, flight, replyTo)
        
        case GetAllFlights(replyTo) =>
          handleGetAllFlights(state, replyTo)
        
        case GetFlight(flightId, replyTo) =>
          handleGetFlight(context, state, flightId, replyTo)
        
        // Wrapped internal messages from message adapters
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
          // Update cache
          val updatedState = state.copy(flightData = state.flightData + (flightId -> flight))
          replyTo ! FlightDetails(flight)
          active(updatedState)
        
        case WrappedGetFlightNotFound(flightId, replyTo) =>
          logger.warn("Flight {} not found in actor", flightId)
          replyTo ! RegistryFlightNotFound(flightId)
          Behaviors.same

        // These should only arrive in `recovering`, but handle gracefully just in case
        case WrappedRecoveryPersistenceIds(_) | WrappedRecoveryComplete(_) | WrappedRecoveryFailed(_) =>
          logger.warn("Received late recovery message in active state — ignoring")
          Behaviors.same
      }
    }
  }

  /**
   * Register a new flight by spawning a FlightActor
   */
  private def handleRegisterFlight(
    context: ActorContext[RegistryCommand],
    state: State,
    flight: Flight,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    
    state.getActor(flight.flightId) match {
      case Some(_) =>
        logger.warn("Flight {} already registered", flight.flightId)
        replyTo ! RegistryOperationFailed(flight.flightId, s"Flight already exists")
        Behaviors.same
      
      case None =>
        logger.info("Registering new flight: {}", flight.flightId)
        
        // Spawn a new FlightActor with supervision
        val flightActor = context.spawn(
          Behaviors.supervise(FlightActor(flight.flightId))
            .onFailure[Exception](
              SupervisorStrategy.restart.withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
            ),
          s"flight-${flight.flightId}"
        )
        
        // Create adapter to handle FlightActor response
        val responseAdapter: ActorRef[FlightResponse] = context.messageAdapter {
          case FlightCreatedResponse(fid) =>
            // Flight successfully created in FlightActor
            WrappedRegisterFlightSuccess(fid, flight, replyTo)
          
          case FlightOperationFailed(_, reason) =>
            WrappedRegisterFlightFailure(flight.flightId, reason, replyTo)
          
          case other =>
            WrappedRegisterFlightFailure(flight.flightId, s"Unexpected response: $other", replyTo)
        }
        
        // Send CreateFlight command to the new FlightActor
        flightActor ! CreateFlight(flight, responseAdapter)
        
        // Update state with actor reference, but DON'T reply yet
        // Wait for WrappedRegisterFlightSuccess to confirm persistence
        val newState = state.addFlight(flight.flightId, flightActor, flight)
        active(newState)
    }
  }

  /**
   * Get all flights
   */
  private def handleGetAllFlights(
    state: State,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    val allFlights = state.getAllFlights
    logger.info("=== GET ALL FLIGHTS ===")
    logger.info("State.flightData.size: {}", state.flightData.size)
    logger.info("getAllFlights result count: {}", allFlights.size)
    logger.info("Flight IDs in state: {}", state.flightData.keys.mkString(", "))
    logger.info("Flight numbers in result: {}", allFlights.map(_.flightNumber).mkString(", "))
    replyTo ! FlightsRetrieved(allFlights)
    Behaviors.same
  }

  /**
   * Get specific flight
   */
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
        // Check if FlightActor exists (from persistence recovery)
        state.getActor(flightId) match {
          case Some(flightActor) =>
            logger.debug("Flight not in cache, querying FlightActor: {}", flightId)
            
            // Create adapter to handle FlightActor response
            val responseAdapter: ActorRef[FlightResponse] = context.messageAdapter {
              case FlightInfo(flight) =>
                WrappedGetFlightSuccess(flightId, flight, replyTo)
              case FlightNotFound(_) =>
                WrappedGetFlightNotFound(flightId, replyTo)
              case other =>
                logger.warn("Unexpected response for GetFlight: {}", other)
                WrappedGetFlightNotFound(flightId, replyTo)
            }
            
            // Query the FlightActor
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

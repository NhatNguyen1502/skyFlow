package com.akka.learning.actors

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

import com.akka.learning.models.domain.{Flight, FlightStatus}
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
    
    def updateFlightData(flightId: String, flight: Flight): State = {
      copy(flightData = flightData + (flightId -> flight))
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
   * Create FlightRegistry behavior
   */
  def apply(): Behavior[RegistryCommand] = {
    Behaviors.setup { context =>
      logger.info("FlightRegistry started at {}", context.self.path)
      active(State())
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
        
        case FindFlightsByRoute(origin, destination, replyTo) =>
          handleFindFlightsByRoute(state, origin, destination, replyTo)
        
        case GetFlight(flightId, replyTo) =>
          handleGetFlight(context, state, flightId, replyTo)
        
        case UpdateFlight(flightId, flightCommand, replyTo) =>
          handleUpdateFlight(context, state, flightId, flightCommand, replyTo)
        
        case UpdateFlightSeats(flightId, seats, replyTo) =>
          handleUpdateFlightSeats(context, state, flightId, seats, replyTo)
        
        case UpdateFlightStatus(flightId, status, replyTo) =>
          handleUpdateFlightStatus(context, state, flightId, status, replyTo)
        
        // Wrapped internal messages from message adapters
        case WrappedRegisterFlightSuccess(flightId, flight, replyTo) =>
          logger.info("Flight {} registered successfully", flightId)
          replyTo ! RegistryFlightCreated(flight)
          Behaviors.same
        
        case WrappedRegisterFlightFailure(flightId, reason, replyTo) =>
          logger.error("Flight {} registration failed: {}", flightId, reason)
          replyTo ! RegistryOperationFailed(flightId, reason)
          Behaviors.same
        
        case WrappedUpdateFlightSuccess(flightId, flight, replyTo) =>
          logger.debug("Flight {} updated successfully", flightId)
          replyTo ! FlightUpdated(flightId, flight)
          Behaviors.same
        
        case WrappedUpdateFlightSeatsSuccess(flightId, availableSeats, replyTo) =>
          logger.debug("Flight {} seats updated to {}", flightId, availableSeats)
          replyTo ! FlightSeatsUpdated(flightId, availableSeats)
          Behaviors.same
        
        case WrappedUpdateFlightStatusSuccess(flightId, status, replyTo) =>
          logger.debug("Flight {} status updated to {}", flightId, status)
          replyTo ! FlightStatusUpdated(flightId, status)
          Behaviors.same
        
        case WrappedUpdateFlightFailure(flightId, reason, replyTo) =>
          logger.error("Flight {} update failed: {}", flightId, reason)
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
    logger.debug("Getting all flights. Total count: {}", state.flightData.size)
    replyTo ! AllFlights(state.getAllFlights)
    Behaviors.same
  }

  /**
   * Find flights by route
   */
  private def handleFindFlightsByRoute(
    state: State,
    origin: String,
    destination: String,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    logger.debug("Finding flights from {} to {}", origin, destination)
    val flights = state.findFlightsByRoute(origin, destination)
    replyTo ! FlightsFound(flights)
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

  /**
   * Update flight by forwarding command to FlightActor
   */
  private def handleUpdateFlight(
    context: ActorContext[RegistryCommand],
    state: State,
    flightId: String,
    flightCommand: FlightCommand,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    
    state.getActor(flightId) match {
      case Some(flightActor) =>
        logger.debug("Forwarding command to FlightActor: {}", flightId)
        
        // Create adapter to convert FlightResponse to RegistryResponse
        val responseAdapter: ActorRef[FlightResponse] = context.messageAdapter {
          case FlightInfo(flight) =>
            WrappedUpdateFlightSuccess(flightId, flight, replyTo)
          
          case SeatsUpdated(fId, availableSeats) =>
            WrappedUpdateFlightSeatsSuccess(fId, availableSeats, replyTo)
          
          case StatusUpdated(fId, status) =>
            WrappedUpdateFlightStatusSuccess(fId, status, replyTo)
          
          case FlightNotFound(fId) =>
            WrappedUpdateFlightFailure(fId, "Flight not found", replyTo)
          
          case InsufficientSeats(fId, requested, available) =>
            WrappedUpdateFlightFailure(fId, s"Insufficient seats: requested $requested, available $available", replyTo)
          
          case FlightOperationFailed(fId, reason) =>
            WrappedUpdateFlightFailure(fId, reason, replyTo)
          
          case other =>
            WrappedUpdateFlightFailure(flightId, s"Unexpected response: $other", replyTo)
        }
        
        // Forward the command with response adapter
        flightCommand match {
          case GetFlightInfo(_, _) =>
            flightActor ! GetFlightInfo(flightId, responseAdapter)
          case UpdateSeats(_, seats, _) =>
            flightActor ! UpdateSeats(flightId, seats, responseAdapter)
          case ReleaseSeats(_, seats, _) =>
            flightActor ! ReleaseSeats(flightId, seats, responseAdapter)
          case ChangeStatus(_, status, _) =>
            flightActor ! ChangeStatus(flightId, status, responseAdapter)
          case _: CreateFlight =>
            // CreateFlight is only for newly spawned actors, shouldn't reach here
            logger.warn("CreateFlight command received for existing flight {}", flightId)
            replyTo ! RegistryOperationFailed(flightId, "Cannot create already existing flight")
        }
        
        Behaviors.same
      
      case None =>
        logger.warn("Flight actor not found: {}", flightId)
        replyTo ! RegistryFlightNotFound(flightId)
        Behaviors.same
    }
  }

  /**
   * Update flight seats (REST API convenience method)
   */
  private def handleUpdateFlightSeats(
    context: ActorContext[RegistryCommand],
    state: State,
    flightId: String,
    seats: Int,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    
    state.getActor(flightId) match {
      case Some(flightActor) =>
        logger.debug("Updating seats for flight {}: {}", flightId, seats)
        
        // Create adapter to handle response
        val responseAdapter: ActorRef[FlightResponse] = context.messageAdapter {
          case SeatsUpdated(fid, availableSeats) =>
            // Update local state
            WrappedUpdateFlightSeatsSuccess(fid, availableSeats, replyTo)
          
          case FlightOperationFailed(_, reason) =>
            WrappedUpdateFlightFailure(flightId, reason, replyTo)
          
          case other =>
            WrappedUpdateFlightFailure(flightId, s"Unexpected response: $other", replyTo)
        }
        
        // Send UpdateSeats command to FlightActor
        flightActor ! UpdateSeats(flightId, seats, responseAdapter)
        
        // Respond with the current flight data from state (will be updated by adapter)
        state.flightData.get(flightId) match {
          case Some(currentFlight) =>
            val updatedFlight = currentFlight.copy(availableSeats = seats)
            replyTo ! RegistryFlightSeatsUpdated(updatedFlight)
            active(state.updateFlightData(flightId, updatedFlight))
          case None =>
            replyTo ! RegistryFlightNotFound(flightId)
            Behaviors.same
        }
      
      case None =>
        logger.warn("Flight actor not found: {}", flightId)
        replyTo ! RegistryFlightNotFound(flightId)
        Behaviors.same
    }
  }

  /**
   * Update flight status (REST API convenience method)
   */
  private def handleUpdateFlightStatus(
    context: ActorContext[RegistryCommand],
    state: State,
    flightId: String,
    status: FlightStatus,
    replyTo: ActorRef[RegistryResponse]
  ): Behavior[RegistryCommand] = {
    
    state.getActor(flightId) match {
      case Some(flightActor) =>
        logger.debug("Updating status for flight {}: {}", flightId, status)
        
        // Create adapter to handle response
        val responseAdapter: ActorRef[FlightResponse] = context.messageAdapter {
          case StatusUpdated(fid, updatedStatus) =>
            // Update local state
            WrappedUpdateFlightStatusSuccess(fid, updatedStatus, replyTo)
          
          case FlightOperationFailed(_, reason) =>
            WrappedUpdateFlightFailure(flightId, reason, replyTo)
          
          case other =>
            WrappedUpdateFlightFailure(flightId, s"Unexpected response: $other", replyTo)
        }
        
        // Send ChangeStatus command to FlightActor
        flightActor ! ChangeStatus(flightId, status, responseAdapter)
        
        // Respond with the current flight data from state (will be updated by adapter)
        state.flightData.get(flightId) match {
          case Some(currentFlight) =>
            val updatedFlight = currentFlight.copy(status = status)
            replyTo ! RegistryFlightStatusUpdated(updatedFlight)
            active(state.updateFlightData(flightId, updatedFlight))
          case None =>
            replyTo ! RegistryFlightNotFound(flightId)
            Behaviors.same
        }
      
      case None =>
        logger.warn("Flight actor not found: {}", flightId)
        replyTo ! RegistryFlightNotFound(flightId)
        Behaviors.same
    }
  }
}

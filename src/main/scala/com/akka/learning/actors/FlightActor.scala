package com.akka.learning.actors

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

import com.akka.learning.models.domain._
import com.akka.learning.models.commands._
import com.akka.learning.models.events._

/**
 * FlightActor - Event Sourced Actor for managing individual flight state
 * 
 * Demonstrates:
 * - EventSourcedBehavior with Akka Persistence
 * - Command handling with validation
 * - Event sourcing pattern
 * - State recovery from events
 * - Supervision strategy
 */
object FlightActor {
  
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Internal state of the FlightActor
   */
  sealed trait State
  
  /**
   * Flight does not exist yet
   */
  case object Empty extends State
  
  /**
   * Flight exists with current data
   */
  case class ActiveFlight(flight: Flight) extends State {
    def updateSeats(seatsAllocated: Int): ActiveFlight = {
      copy(flight = flight.copy(availableSeats = flight.availableSeats - seatsAllocated))
    }
    
    def releaseSeats(seatsReleased: Int): ActiveFlight = {
      copy(flight = flight.copy(availableSeats = flight.availableSeats + seatsReleased))
    }
    
    def updateStatus(newStatus: FlightStatus): ActiveFlight = {
      copy(flight = flight.copy(status = newStatus))
    }
  }

  /**
   * Create a new FlightActor behavior
   * 
   * @param flightId Unique identifier for the flight
   * @return EventSourcedBehavior for this flight
   */
  def apply(flightId: String): Behavior[FlightCommand] = {
    EventSourcedBehavior[FlightCommand, FlightEvent, State](
      persistenceId = PersistenceId.ofUniqueId(s"flight-$flightId"),
      emptyState = Empty,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withRetention(
      RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
    ).onPersistFailure(
      SupervisorStrategy.restartWithBackoff(
        minBackoff = 1.second,
        maxBackoff = 10.seconds,
        randomFactor = 0.1
      )
    )
  }

  /**
   * Command handler - processes commands and produces effects
   */
  private def commandHandler: (State, FlightCommand) => Effect[FlightEvent, State] = {
    (state, command) =>
      state match {
        case Empty =>
          command match {
            case CreateFlight(flight, replyTo) =>
              logger.info("Creating flight: {}", flight.flightId)
              Effect
                .persist(FlightCreated(flight.flightId, flight))
                .thenReply(replyTo)(_ => FlightCreatedResponse(flight.flightId))
            
            case _: GetFlightInfo =>
              Effect.none.thenReply(command.asInstanceOf[GetFlightInfo].replyTo)(_ => 
                FlightNotFound(flightId = "unknown")
              )
            
            case _ =>
              Effect.unhandled.thenNoReply()
          }
        
        case ActiveFlight(flight) =>
          command match {
            case CreateFlight(_, replyTo) =>
              logger.warn("Flight {} already exists", flight.flightId)
              Effect.none.thenReply(replyTo)(_ => 
                FlightOperationFailed(flight.flightId, "Flight already exists")
              )
            
            case GetFlightInfo(_, replyTo) =>
              logger.debug("Getting flight info for: {}", flight.flightId)
              Effect.none.thenReply(replyTo)(_ => FlightInfo(flight))
            
            case UpdateSeats(fId, seatsToAllocate, replyTo) =>
              if (flight.hasCapacity(seatsToAllocate)) {
                val remainingSeats = flight.availableSeats - seatsToAllocate
                logger.info("Allocating {} seats on flight {}. Remaining: {}", 
                  seatsToAllocate, flight.flightId, remainingSeats)
                Effect
                  .persist(SeatsAllocated(fId, seatsToAllocate, remainingSeats))
                  .thenReply(replyTo)(_ => SeatsUpdated(fId, remainingSeats))
              } else {
                logger.warn("Insufficient seats on flight {}. Requested: {}, Available: {}", 
                  flight.flightId, seatsToAllocate, flight.availableSeats)
                Effect.none.thenReply(replyTo)(_ => 
                  InsufficientSeats(fId, seatsToAllocate, flight.availableSeats)
                )
              }
            
            case ReleaseSeats(fId, seatsToRelease, replyTo) =>
              val remainingSeats = flight.availableSeats + seatsToRelease
              logger.info("Releasing {} seats on flight {}. New available: {}", 
                seatsToRelease, flight.flightId, remainingSeats)
              Effect
                .persist(SeatsReleased(fId, seatsToRelease, remainingSeats))
                .thenReply(replyTo)(_ => SeatsUpdated(fId, remainingSeats))
            
            case ChangeStatus(fId, newStatus, replyTo) =>
              if (newStatus != flight.status) {
                logger.info("Changing flight {} status from {} to {}", 
                  flight.flightId, flight.status, newStatus)
                Effect
                  .persist(FlightStatusChanged(fId, flight.status, newStatus))
                  .thenReply(replyTo)(_ => StatusUpdated(fId, newStatus))
              } else {
                logger.debug("Flight {} already has status {}", flight.flightId, newStatus)
                Effect.none.thenReply(replyTo)(_ => StatusUpdated(fId, newStatus))
              }
          }
      }
  }

  /**
   * Event handler - updates state based on persisted events
   */
  private def eventHandler: (State, FlightEvent) => State = {
    (state, event) =>
      state match {
        case Empty =>
          event match {
            case FlightCreated(_, flight, _) =>
              logger.debug("Event: Flight created - {}", flight.flightId)
              ActiveFlight(flight)
            case _ =>
              logger.warn("Unexpected event in Empty state: {}", event)
              state
          }
        
        case active: ActiveFlight =>
          event match {
            case SeatsAllocated(_, seatsAllocated, remainingSeats, _) =>
              logger.debug("Event: Seats allocated - {} seats, {} remaining", 
                seatsAllocated, remainingSeats)
              active.updateSeats(seatsAllocated)
            
            case SeatsReleased(_, seatsReleased, remainingSeats, _) =>
              logger.debug("Event: Seats released - {} seats, {} remaining", 
                seatsReleased, remainingSeats)
              active.releaseSeats(seatsReleased)
            
            case FlightStatusChanged(_, oldStatus, newStatus, _) =>
              logger.debug("Event: Status changed from {} to {}", oldStatus, newStatus)
              active.updateStatus(newStatus)
            
            case FlightCancelled(_, reason, _) =>
              logger.info("Event: Flight cancelled - {}", reason)
              active.updateStatus(FlightStatus.Cancelled)
            
            case _ =>
              logger.warn("Unexpected event in ActiveFlight state: {}", event)
              state
          }
      }
  }
}

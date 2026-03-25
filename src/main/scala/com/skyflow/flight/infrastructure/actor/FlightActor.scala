package com.skyflow.flight.infrastructure.actor

import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

import com.skyflow.flight.domain.model.Flight
import com.skyflow.flight.domain.event._
import com.skyflow.flight.application.command._

/**
 * FlightActor — Event Sourced Actor for individual flight state.
 *
 * This actor is an infrastructure ADAPTER: it delegates business logic
 * to the Flight aggregate root and only handles persistence concerns.
 */
object FlightActor {

  private val logger = LoggerFactory.getLogger(getClass)

  sealed trait State
  case object Empty extends State
  case class ActiveFlight(flight: Flight) extends State

  def apply(flightId: String): Behavior[FlightCommand] = {
    EventSourcedBehavior[FlightCommand, FlightDomainEvent, State](
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
   * Command handler — delegates to domain model for business logic.
   */
  private def commandHandler: (State, FlightCommand) => Effect[FlightDomainEvent, State] = {
    (state, command) =>
      state match {
        case Empty =>
          command match {
            case CreateFlight(flight, replyTo) =>
              logger.info("Creating flight: {}", flight.id.value)
              val event = FlightCreatedEvent(flight.id.value, flight)
              Effect
                .persist(event)
                .thenReply(replyTo)(_ => FlightCreatedResponse(flight.id.value))

            case GetFlightInfo(_, replyTo) =>
              Effect.none.thenReply(replyTo)(_ => FlightNotFound("unknown"))

            case _ =>
              Effect.unhandled.thenNoReply()
          }

        case ActiveFlight(flight) =>
          command match {
            case CreateFlight(_, replyTo) =>
              logger.warn("Flight {} already exists", flight.id.value)
              Effect.none.thenReply(replyTo)(_ =>
                FlightOperationFailed(flight.id.value, "Flight already exists")
              )

            case GetFlightInfo(_, replyTo) =>
              logger.debug("Getting flight info for: {}", flight.id.value)
              Effect.none.thenReply(replyTo)(_ => FlightInfo(flight))

            case _ =>
              Effect.unhandled.thenNoReply()
          }
      }
  }

  /**
   * Event handler — rebuilds state from persisted events.
   */
  private def eventHandler: (State, FlightDomainEvent) => State = { (state, event) =>
    state match {
      case Empty =>
        event match {
          case FlightCreatedEvent(_, flight, _) =>
            logger.debug("Event: Flight created - {}", flight.id.value)
            ActiveFlight(flight)
          case _ =>
            logger.warn("Unexpected event in Empty state: {}", event)
            state
        }

      case active @ ActiveFlight(flight) =>
        event match {
          case FlightSeatsAllocated(_, seats, remaining, _) =>
            ActiveFlight(flight.copy(availableSeats = remaining))
          case FlightSeatsReleased(_, _, remaining, _) =>
            ActiveFlight(flight.copy(availableSeats = remaining))
          case FlightStatusUpdated(_, _, newStatus, _) =>
            ActiveFlight(flight.copy(status = newStatus))
          case FlightWasCancelled(_, _, _) =>
            ActiveFlight(flight.copy(status = com.skyflow.flight.domain.model.FlightStatus.Cancelled))
          case _ =>
            logger.warn("Unexpected event in ActiveFlight state: {}", event)
            active
        }
    }
  }
}

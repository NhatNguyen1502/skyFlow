package com.akka.learning.actors

import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

import com.akka.learning.models.domain.Flight
import com.akka.learning.models.commands._
import com.akka.learning.models.events._

/** FlightActor - Event Sourced Actor for managing individual flight state
  *
  * Demonstrates:
  *   - EventSourcedBehavior with Akka Persistence
  *   - Command handling with validation
  *   - Event sourcing pattern
  *   - State recovery from events
  *   - Supervision strategy
  */
object FlightActor {

  private val logger = LoggerFactory.getLogger(getClass)

  sealed trait State

  case object Empty extends State
  case class ActiveFlight(flight: Flight) extends State {}

  /** Create a new FlightActor behavior
    *
    * @param flightId
    *   Unique identifier for the flight
    * @return
    *   EventSourcedBehavior for this flight
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

  /** Command handler - processes commands and produces effects
    */
  private def commandHandler
      : (State, FlightCommand) => Effect[FlightEvent, State] = {
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
              Effect.none.thenReply(
                command.asInstanceOf[GetFlightInfo].replyTo
              )(_ => FlightNotFound(flightId = "unknown"))

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

            case _ =>
              Effect.unhandled.thenNoReply()
          }
      }
  }

  /** Event handler - updates state based on persisted events
    */
  private def eventHandler: (State, FlightEvent) => State = { (state, event) =>
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
          case _ =>
            logger.warn("Unexpected event in ActiveFlight state: {}", event)
            state
        }
    }
  }
}

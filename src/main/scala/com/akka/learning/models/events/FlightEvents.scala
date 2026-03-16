package com.akka.learning.models.events

import java.time.LocalDateTime
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.{Flight, FlightStatus}

/**
 * Flight Events for Event Sourcing
 * 
 * Events represent facts that have happened to a flight.
 * These are persisted in the event journal and used to rebuild state.
 */

/**
 * Base trait for all Flight events
 */
sealed trait FlightEvent extends CborSerializable {
  def flightId: String
  def timestamp: LocalDateTime
}

/**
 * Event: A flight was created
 */
case class FlightCreated(
  flightId: String,
  flight: Flight,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightEvent

/**
 * Event: Seats were allocated on a flight
 */
case class SeatsAllocated(
  flightId: String,
  seatsAllocated: Int,
  remainingSeats: Int,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightEvent

/**
 * Event: Seats were released (e.g., cancellation)
 */
case class SeatsReleased(
  flightId: String,
  seatsReleased: Int,
  remainingSeats: Int,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightEvent

/**
 * Event: Flight status changed
 */
case class FlightStatusChanged(
  flightId: String,
  oldStatus: FlightStatus,
  newStatus: FlightStatus,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightEvent

/**
 * Event: Flight was cancelled
 */
case class FlightCancelled(
  flightId: String,
  reason: String,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightEvent

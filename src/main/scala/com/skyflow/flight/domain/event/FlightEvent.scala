package com.skyflow.flight.domain.event

import java.time.LocalDateTime
import com.skyflow.flight.domain.model.{Flight, FlightStatus}
import com.skyflow.shared.infrastructure.serialization.CborSerializable

/**
 * FlightDomainEvent — Pure domain events for the Flight aggregate.
 *
 * These events represent business facts that have occurred.
 * They extend CborSerializable for Akka Persistence compatibility.
 */
sealed trait FlightDomainEvent extends CborSerializable {
  def flightId: String
  def timestamp: LocalDateTime
}

case class FlightCreatedEvent(
  flightId: String,
  flight: Flight,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightDomainEvent

case class FlightSeatsAllocated(
  flightId: String,
  seatsAllocated: Int,
  remainingSeats: Int,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightDomainEvent

case class FlightSeatsReleased(
  flightId: String,
  seatsReleased: Int,
  remainingSeats: Int,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightDomainEvent

case class FlightStatusUpdated(
  flightId: String,
  oldStatus: FlightStatus,
  newStatus: FlightStatus,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightDomainEvent

case class FlightWasCancelled(
  flightId: String,
  reason: String,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends FlightDomainEvent

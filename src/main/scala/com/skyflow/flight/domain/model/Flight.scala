package com.skyflow.flight.domain.model

import java.time.LocalDateTime
import com.skyflow.flight.domain.error.FlightError
import com.skyflow.flight.domain.event._

/**
 * Flight — Aggregate Root (Rich Domain Model).
 *
 * Contains business logic, validates invariants, and produces domain events.
 * The ONLY way to create a valid Flight is through the `Flight.create` factory method.
 * All state transitions return Either[FlightError, (Flight, DomainEvent)].
 */
case class Flight(
  id: FlightId,
  flightNumber: FlightNumber,
  route: Route,
  scheduledDeparture: LocalDateTime,
  scheduledArrival: LocalDateTime,
  totalSeats: SeatCapacity,
  availableSeats: Int,
  status: FlightStatus
) {

  // ── Queries ──

  def occupiedSeats: Int = totalSeats.value - availableSeats
  def isFullyBooked: Boolean = availableSeats <= 0
  def hasCapacity(seats: Int): Boolean = availableSeats >= seats

  // ── Commands (business logic with invariant protection) ──

  def allocateSeats(count: Int): Either[FlightError, (Flight, FlightDomainEvent)] = {
    if (count <= 0)
      Left(FlightError.ValidationFailed("Seat count must be positive"))
    else if (status == FlightStatus.Cancelled)
      Left(FlightError.FlightCancelled(id))
    else if (!hasCapacity(count))
      Left(FlightError.InsufficientSeats(id, count, availableSeats))
    else {
      val updated = copy(availableSeats = availableSeats - count)
      val event = FlightSeatsAllocated(id.value, count, updated.availableSeats)
      Right((updated, event))
    }
  }

  def releaseSeats(count: Int): Either[FlightError, (Flight, FlightDomainEvent)] = {
    if (count <= 0)
      Left(FlightError.ValidationFailed("Seat count must be positive"))
    else if (status == FlightStatus.Cancelled)
      Left(FlightError.FlightCancelled(id))
    else {
      val newAvailable = math.min(availableSeats + count, totalSeats.value)
      val updated = copy(availableSeats = newAvailable)
      val event = FlightSeatsReleased(id.value, count, updated.availableSeats)
      Right((updated, event))
    }
  }

  def changeStatus(newStatus: FlightStatus): Either[FlightError, (Flight, FlightDomainEvent)] = {
    if (status == newStatus)
      Left(FlightError.InvalidStatusTransition(status.toString, newStatus.toString))
    else if (status == FlightStatus.Cancelled)
      Left(FlightError.FlightCancelled(id))
    else {
      val updated = copy(status = newStatus)
      val event = FlightStatusUpdated(id.value, status, newStatus)
      Right((updated, event))
    }
  }

  def cancel(reason: String): Either[FlightError, (Flight, FlightDomainEvent)] = {
    if (status == FlightStatus.Cancelled)
      Left(FlightError.FlightCancelled(id))
    else {
      val updated = copy(status = FlightStatus.Cancelled)
      val event = FlightWasCancelled(id.value, reason)
      Right((updated, event))
    }
  }
}

object Flight {

  /**
   * Factory method — the ONLY valid way to create a new Flight.
   * Validates all invariants and returns a domain event if successful.
   */
  def create(
    flightNumber: FlightNumber,
    route: Route,
    scheduledDeparture: LocalDateTime,
    scheduledArrival: LocalDateTime,
    totalSeats: SeatCapacity
  ): Either[FlightError, (Flight, FlightDomainEvent)] = {
    if (!scheduledArrival.isAfter(scheduledDeparture))
      Left(FlightError.InvalidSchedule("Arrival must be after departure"))
    else {
      val id = FlightId.generate()
      val flight = Flight(
        id = id,
        flightNumber = flightNumber,
        route = route,
        scheduledDeparture = scheduledDeparture,
        scheduledArrival = scheduledArrival,
        totalSeats = totalSeats,
        availableSeats = totalSeats.value,
        status = FlightStatus.Scheduled
      )
      val event = FlightCreatedEvent(id.value, flight)
      Right((flight, event))
    }
  }

  /**
   * Reconstruct a Flight from persisted event data (for event sourcing recovery).
   * Bypasses validation since data was already validated at creation time.
   */
  def fromPersistence(
    id: String,
    flightNumber: String,
    route: Route,
    scheduledDeparture: LocalDateTime,
    scheduledArrival: LocalDateTime,
    totalSeats: Int,
    availableSeats: Int,
    status: FlightStatus
  ): Flight = Flight(
    id = FlightId(id),
    flightNumber = FlightNumber(flightNumber),
    route = route,
    scheduledDeparture = scheduledDeparture,
    scheduledArrival = scheduledArrival,
    totalSeats = SeatCapacity(totalSeats),
    availableSeats = availableSeats,
    status = status
  )
}

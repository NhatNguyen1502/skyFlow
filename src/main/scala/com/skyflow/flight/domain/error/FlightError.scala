package com.skyflow.flight.domain.error

import com.skyflow.flight.domain.model.FlightId

/**
 * FlightError — Domain-specific errors for the Flight bounded context.
 */
sealed trait FlightError {
  def message: String
}

object FlightError {
  case class ValidationFailed(message: String) extends FlightError
  case class InvalidAirportCode(code: String) extends FlightError {
    val message: String = s"Invalid IATA airport code: '$code'"
  }
  case class InvalidFlightNumber(number: String) extends FlightError {
    val message: String = s"Invalid flight number: '$number'"
  }
  case class InvalidSeatCapacity(seats: Int) extends FlightError {
    val message: String = s"Invalid seat capacity: $seats"
  }
  case class InvalidSchedule(reason: String) extends FlightError {
    val message: String = s"Invalid schedule: $reason"
  }
  case class InsufficientSeats(flightId: FlightId, requested: Int, available: Int) extends FlightError {
    val message: String = s"Flight ${flightId.value}: requested $requested seats but only $available available"
  }
  case class FlightCancelled(flightId: FlightId) extends FlightError {
    val message: String = s"Flight ${flightId.value} is cancelled"
  }
  case class FlightAlreadyExists(flightId: FlightId) extends FlightError {
    val message: String = s"Flight ${flightId.value} already exists"
  }
  case class FlightNotFound(flightId: String) extends FlightError {
    val message: String = s"Flight $flightId not found"
  }
  case class InvalidStatusTransition(from: String, to: String) extends FlightError {
    val message: String = s"Cannot transition from $from to $to"
  }
}

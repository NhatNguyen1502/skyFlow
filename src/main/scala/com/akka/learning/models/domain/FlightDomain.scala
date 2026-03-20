package com.akka.learning.models.domain

import java.time.{LocalDateTime, Duration}
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.{DeserializationContext, DeserializationFeature}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

/**
 * Flight Domain Models
 * 
 * Core business domain for flight operations including:
 * - Airport locations and routes
 * - Flight entities with capacity management
 * - Flight status lifecycle
 */

/**
 * Represents an airport location
 */
case class Airport(code: String, name: String, city: String, country: String)

/**
 * Jackson deserializer: maps "Scheduled" → FlightStatus.Scheduled etc.
 * Needed because Jackson cannot construct sealed trait case objects by default.
 */
class FlightStatusDeserializer extends StdDeserializer[FlightStatus](classOf[FlightStatus]) {
  override def deserialize(p: JsonParser, ctxt: DeserializationContext): FlightStatus =
    p.getText match {
      case "Scheduled" => FlightStatus.Scheduled
      case "Boarding"  => FlightStatus.Boarding
      case "Departed"  => FlightStatus.Departed
      case "Arrived"   => FlightStatus.Arrived
      case "Cancelled" => FlightStatus.Cancelled
      case "Delayed"   => FlightStatus.Delayed
      case other       => throw ctxt.instantiationException(classOf[FlightStatus], s"Unknown FlightStatus: $other")
    }
}

/**
 * Flight status enumeration.
 *
 * @JsonValue  — tells Jackson to serialize using toString (e.g. "Scheduled"), not as an object.
 * @JsonDeserialize — tells Jackson to use our custom deserializer when reading back.
 */
@JsonDeserialize(using = classOf[FlightStatusDeserializer])
sealed trait FlightStatus {
  /** Used by Jackson (@JsonValue) to serialize as a plain string. */
  @com.fasterxml.jackson.annotation.JsonValue
  override def toString: String = getClass.getSimpleName.stripSuffix("$")
}
object FlightStatus {
  case object Scheduled extends FlightStatus
  case object Boarding extends FlightStatus
  case object Departed extends FlightStatus
  case object Arrived extends FlightStatus
  case object Cancelled extends FlightStatus
  case object Delayed extends FlightStatus
}

/**
 * Represents a flight route
 */
case class Route(
  origin: Airport,
  destination: Airport,
  distance: Double, // in kilometers
  estimatedDuration: Duration
)

/**
 * Represents a flight with capacity and current state
 */
case class Flight(
  flightId: String,
  flightNumber: String,
  route: Route,
  scheduledDeparture: LocalDateTime,
  scheduledArrival: LocalDateTime,
  totalSeats: Int,
  availableSeats: Int,
  status: FlightStatus
) {
  def occupiedSeats: Int = totalSeats - availableSeats
  def isFullyBooked: Boolean = availableSeats <= 0
  def hasCapacity(seats: Int): Boolean = availableSeats >= seats
}

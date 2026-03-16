package com.akka.learning.models.domain

import java.time.{LocalDateTime, Duration}

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
 * Flight status enumeration
 */
sealed trait FlightStatus
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

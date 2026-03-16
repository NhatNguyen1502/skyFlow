package com.akka.learning.models.commands

import akka.actor.typed.ActorRef
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain._

/**
 * Flight Actor Commands & Responses
 * 
 * Command messages for the FlightActor using the Ask pattern.
 * Each command carries a replyTo ActorRef for the response.
 */

/**
 * Base trait for all Flight Actor commands
 */
sealed trait FlightCommand extends CborSerializable

/**
 * Create a new flight
 */
case class CreateFlight(
  flight: Flight,
  replyTo: ActorRef[FlightResponse]
) extends FlightCommand

/**
 * Get flight information
 */
case class GetFlightInfo(
  flightId: String,
  replyTo: ActorRef[FlightResponse]
) extends FlightCommand

/**
 * Update available seats
 */
case class UpdateSeats(
  flightId: String,
  seatsToAllocate: Int,
  replyTo: ActorRef[FlightResponse]
) extends FlightCommand

/**
 * Change flight status
 */
case class ChangeStatus(
  flightId: String,
  newStatus: FlightStatus,
  replyTo: ActorRef[FlightResponse]
) extends FlightCommand

/**
 * Release allocated seats (e.g., cancellation)
 */
case class ReleaseSeats(
  flightId: String,
  seatsToRelease: Int,
  replyTo: ActorRef[FlightResponse]
) extends FlightCommand

/**
 * Responses from Flight Actor
 */
sealed trait FlightResponse extends CborSerializable

case class FlightInfo(flight: Flight) extends FlightResponse
case class FlightCreated(flightId: String) extends FlightResponse
case class SeatsUpdated(flightId: String, availableSeats: Int) extends FlightResponse
case class StatusUpdated(flightId: String, status: FlightStatus) extends FlightResponse
case class FlightNotFound(flightId: String) extends FlightResponse
case class InsufficientSeats(flightId: String, requested: Int, available: Int) extends FlightResponse
case class FlightOperationFailed(flightId: String, reason: String) extends FlightResponse

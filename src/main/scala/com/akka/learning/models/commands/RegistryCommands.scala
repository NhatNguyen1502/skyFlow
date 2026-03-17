package com.akka.learning.models.commands

import akka.actor.typed.ActorRef
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.{Flight, FlightStatus}

/**
 * Flight Registry Commands & Responses
 * 
 * Command messages for the FlightRegistry actor that manages
 * multiple FlightActor instances.
 */

/**
 * Base trait for Flight Registry commands
 */
sealed trait RegistryCommand extends CborSerializable

/**
 * Internal commands (used by actor implementation only, not exposed to external API)
 */
private[learning] case class WrappedRegisterFlightSuccess(
  flightId: String,
  flight: Flight,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedRegisterFlightFailure(
  flightId: String, 
  reason: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedUpdateFlightSuccess(
  flightId: String, 
  flight: Flight,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedUpdateFlightSeatsSuccess(
  flightId: String,
  availableSeats: Int,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedUpdateFlightStatusSuccess(
  flightId: String,
  status: FlightStatus,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedUpdateFlightFailure(
  flightId: String,
  reason: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedGetFlightSuccess(
  flightId: String,
  flight: Flight,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedGetFlightNotFound(
  flightId: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Register a new flight in the registry
 */
case class RegisterFlight(
  flight: Flight,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Get all flights
 */
case class GetAllFlights(
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Find flights by route
 */
case class FindFlightsByRoute(
  origin: String,
  destination: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Get specific flight from registry
 */
case class GetFlight(
  flightId: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Update flight in registry
 */
case class UpdateFlight(
  flightId: String,
  command: FlightCommand,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Update flight seats (convenience command for REST API)
 */
case class UpdateFlightSeats(
  flightId: String,
  seats: Int,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Update flight status (convenience command for REST API)
 */
case class UpdateFlightStatus(
  flightId: String,
  status: FlightStatus,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

/**
 * Responses from Flight Registry
 */
sealed trait RegistryResponse extends CborSerializable

case class FlightRegistered(flightId: String) extends RegistryResponse
case class RegistryFlightCreated(flight: Flight) extends RegistryResponse
case class AllFlights(flights: List[Flight]) extends RegistryResponse
case class FlightsRetrieved(flights: List[Flight]) extends RegistryResponse
case class FlightsFound(flights: List[Flight]) extends RegistryResponse
case class FlightDetails(flight: Flight) extends RegistryResponse
case class FlightRetrieved(flight: Option[Flight]) extends RegistryResponse
case class FlightUpdated(flightId: String, flight: Flight) extends RegistryResponse
case class RegistryFlightUpdated(flight: Flight) extends RegistryResponse
case class FlightSeatsUpdated(flightId: String, availableSeats: Int) extends RegistryResponse
case class RegistryFlightSeatsUpdated(flight: Flight) extends RegistryResponse
case class FlightStatusUpdated(flightId: String, status: FlightStatus) extends RegistryResponse
case class RegistryFlightStatusUpdated(flight: Flight) extends RegistryResponse
case class RegistryFlightNotFound(flightId: String) extends RegistryResponse
case class RegistryOperationFailed(flightId: String, reason: String) extends RegistryResponse

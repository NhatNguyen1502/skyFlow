package com.akka.learning.models.commands

import akka.actor.typed.ActorRef
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.Flight

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
 * Responses from Flight Registry
 */
sealed trait RegistryResponse extends CborSerializable

case class FlightRegistered(flightId: String) extends RegistryResponse
case class AllFlights(flights: List[Flight]) extends RegistryResponse
case class FlightsFound(flights: List[Flight]) extends RegistryResponse
case class FlightDetails(flight: Flight) extends RegistryResponse
case class RegistryFlightNotFound(flightId: String) extends RegistryResponse
case class RegistryOperationFailed(reason: String) extends RegistryResponse

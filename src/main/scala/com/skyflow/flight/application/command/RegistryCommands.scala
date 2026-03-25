package com.skyflow.flight.application.command

import akka.actor.typed.ActorRef
import com.skyflow.flight.domain.model.Flight
import com.skyflow.shared.infrastructure.serialization.CborSerializable

/**
 * RegistryCommand — Commands for the FlightRegistry supervisor actor.
 */
sealed trait RegistryCommand extends CborSerializable

case class RegisterFlight(
  flight: Flight,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

case class GetAllFlights(
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

case class GetFlight(
  flightId: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

// ── Internal wrapper messages (used by actor only) ──

private[skyflow] case class WrappedRegisterFlightSuccess(
  flightId: String,
  flight: Flight,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[skyflow] case class WrappedRegisterFlightFailure(
  flightId: String,
  reason: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[skyflow] case class WrappedGetFlightSuccess(
  flightId: String,
  flight: Flight,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[skyflow] case class WrappedGetFlightNotFound(
  flightId: String,
  replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[skyflow] case class WrappedRecoveryPersistenceIds(ids: List[String])
  extends RegistryCommand

private[skyflow] case class WrappedRecoveryComplete(
  recoveredFlights: List[(String, ActorRef[FlightCommand], Flight)]
) extends RegistryCommand

private[skyflow] case class WrappedRecoveryFailed(reason: String)
  extends RegistryCommand

/**
 * Responses from FlightRegistry.
 */
sealed trait RegistryResponse extends CborSerializable

case class RegistryFlightCreated(flight: Flight) extends RegistryResponse
case class FlightsRetrieved(flights: List[Flight]) extends RegistryResponse
case class FlightDetails(flight: Flight) extends RegistryResponse
case class RegistryFlightNotFound(flightId: String) extends RegistryResponse
case class RegistryOperationFailed(flightId: String, reason: String) extends RegistryResponse

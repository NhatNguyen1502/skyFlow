package com.skyflow.flight.application.command

import akka.actor.typed.ActorRef
import com.skyflow.flight.domain.model.Flight
import com.skyflow.shared.infrastructure.serialization.CborSerializable

/**
 * FlightCommand — Application-layer commands for FlightActor.
 * These carry ActorRef for the Ask pattern (infrastructure concern).
 */
sealed trait FlightCommand extends CborSerializable

case class CreateFlight(
  flight: Flight,
  replyTo: ActorRef[FlightResponse]
) extends FlightCommand

case class GetFlightInfo(
  flightId: String,
  replyTo: ActorRef[FlightResponse]
) extends FlightCommand

/**
 * Responses from FlightActor.
 */
sealed trait FlightResponse extends CborSerializable

case class FlightInfo(flight: Flight) extends FlightResponse
case class FlightCreatedResponse(flightId: String) extends FlightResponse
case class FlightNotFound(flightId: String) extends FlightResponse
case class FlightOperationFailed(flightId: String, reason: String) extends FlightResponse

package com.akka.learning.models.commands

import akka.actor.typed.ActorRef
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.Flight

/** Flight Actor Commands & Responses
  *
  * Command messages for the FlightActor using the Ask pattern. Each command
  * carries a replyTo ActorRef for the response.
  */

/** Base trait for all Flight Actor commands
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

/** Responses from Flight Actor
  */
sealed trait FlightResponse extends CborSerializable

case class FlightInfo(flight: Flight) extends FlightResponse
case class FlightCreatedResponse(flightId: String) extends FlightResponse
case class FlightNotFound(flightId: String) extends FlightResponse
case class FlightOperationFailed(flightId: String, reason: String)
    extends FlightResponse

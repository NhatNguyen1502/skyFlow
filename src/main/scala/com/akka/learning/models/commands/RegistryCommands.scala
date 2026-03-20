package com.akka.learning.models.commands

import akka.actor.typed.ActorRef
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.Flight

/** Flight Registry Commands & Responses
  *
  * Command messages for the FlightRegistry actor that manages multiple
  * FlightActor instances.
  */

/** Base trait for Flight Registry commands
  */
sealed trait RegistryCommand extends CborSerializable

/** Internal commands (used by actor implementation only, not exposed to
  * external API)
  */

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

private[learning] case class WrappedGetFlightSuccess(
    flightId: String,
    flight: Flight,
    replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

private[learning] case class WrappedGetFlightNotFound(
    flightId: String,
    replyTo: ActorRef[RegistryResponse]
) extends RegistryCommand

// Recovery messages — sent via pipeToSelf during startup
// Step 1: IDs fetched from journal (no actor context needed)
private[learning] case class WrappedRecoveryPersistenceIds(ids: List[String])
    extends RegistryCommand

// Step 2: All actors spawned + states queried
private[learning] case class WrappedRecoveryComplete(
    recoveredFlights: List[(String, ActorRef[FlightCommand], Flight)]
) extends RegistryCommand

private[learning] case class WrappedRecoveryFailed(reason: String)
    extends RegistryCommand

/** Responses from Flight Registry
  */
sealed trait RegistryResponse extends CborSerializable

case class FlightRegistered(flightId: String) extends RegistryResponse
case class RegistryFlightCreated(flight: Flight) extends RegistryResponse
case class AllFlights(flights: List[Flight]) extends RegistryResponse
case class FlightsRetrieved(flights: List[Flight]) extends RegistryResponse
case class FlightsFound(flights: List[Flight]) extends RegistryResponse
case class FlightDetails(flight: Flight) extends RegistryResponse
case class FlightRetrieved(flight: Option[Flight]) extends RegistryResponse
case class RegistryFlightNotFound(flightId: String) extends RegistryResponse
case class RegistryOperationFailed(flightId: String, reason: String)
    extends RegistryResponse

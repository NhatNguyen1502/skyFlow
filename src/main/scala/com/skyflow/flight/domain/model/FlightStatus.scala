package com.skyflow.flight.domain.model

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

/**
 * Jackson deserializer for FlightStatus sealed trait.
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
 * FlightStatus — Enum Value Object representing the lifecycle state of a flight.
 */
@JsonDeserialize(using = classOf[FlightStatusDeserializer])
sealed trait FlightStatus {
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

  def from(status: String): Either[String, FlightStatus] = status match {
    case "Scheduled" => Right(Scheduled)
    case "Boarding"  => Right(Boarding)
    case "Departed"  => Right(Departed)
    case "Arrived"   => Right(Arrived)
    case "Cancelled" => Right(Cancelled)
    case "Delayed"   => Right(Delayed)
    case other       => Left(s"Unknown flight status: '$other'")
  }
}

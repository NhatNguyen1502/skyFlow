package com.skyflow.flight.domain.model

import java.util.UUID

/**
 * FlightId — Value Object for unique flight identification.
 */
final case class FlightId(value: String) {
  override def toString: String = value
}

object FlightId {
  def generate(): FlightId = FlightId(UUID.randomUUID().toString)

  def from(id: String): Either[String, FlightId] =
    if (id.trim.nonEmpty) Right(FlightId(id.trim))
    else Left("Flight ID cannot be empty")
}

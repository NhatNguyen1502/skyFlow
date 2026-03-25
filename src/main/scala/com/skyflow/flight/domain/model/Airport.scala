package com.skyflow.flight.domain.model

import com.skyflow.shared.domain.AirportCode

/**
 * Airport — Value Object representing an airport location.
 * Contains the validated IATA code plus descriptive information.
 */
final case class Airport(
  code: AirportCode,
  name: String,
  city: String,
  country: String
)

object Airport {
  def from(
    code: String,
    name: String,
    city: String,
    country: String
  ): Either[String, Airport] =
    AirportCode.from(code).map(c => Airport(c, name, city, country))
}

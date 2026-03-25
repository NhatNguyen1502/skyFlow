package com.skyflow.flight.domain.model

import java.time.Duration

/**
 * Route — Value Object representing a flight route between two airports.
 */
final case class Route(
  origin: Airport,
  destination: Airport,
  distance: Double,
  estimatedDuration: Duration
)

object Route {
  def from(
    origin: Airport,
    destination: Airport,
    distance: Double,
    estimatedDuration: Duration
  ): Either[String, Route] = {
    if (origin.code == destination.code)
      Left(s"Origin and destination cannot be the same airport: ${origin.code}")
    else if (distance <= 0)
      Left(s"Distance must be positive, got: $distance")
    else if (estimatedDuration.isNegative || estimatedDuration.isZero)
      Left("Estimated duration must be positive")
    else
      Right(Route(origin, destination, distance, estimatedDuration))
  }
}

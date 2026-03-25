package com.skyflow.allocation.domain.model

import com.skyflow.shared.domain.AirportCode

/**
 * ODPair — Value Object representing an Origin-Destination pair.
 *
 * In the Allocation context, we only care about airport codes (not full Airport details).
 * This keeps the Allocation context decoupled from the Flight context.
 */
final case class ODPair(origin: AirportCode, destination: AirportCode) {
  def toKey: String = s"${origin.value}-${destination.value}"
}

object ODPair {
  def from(originCode: String, destinationCode: String): Either[String, ODPair] =
    for {
      origin <- AirportCode.from(originCode)
      dest   <- AirportCode.from(destinationCode)
      _      <- if (origin == dest) Left(s"Origin and destination cannot be the same: ${origin.value}") else Right(())
    } yield ODPair(origin, dest)
}

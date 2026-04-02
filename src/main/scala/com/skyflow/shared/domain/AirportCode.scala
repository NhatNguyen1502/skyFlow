package com.skyflow.shared.domain

/** AirportCode — Shared Value Object (IATA 3-letter code).
  *
  * Used across Flight and Allocation bounded contexts.
  */
final case class AirportCode(value: String) {
  override def toString: String = value
}

object AirportCode {
  private val IataPattern = "^[A-Z]{3}$".r

  def from(code: String): Either[String, AirportCode] = {
    val upper = code.trim.toUpperCase
    upper match {
      case IataPattern() => Right(AirportCode(upper))
      case _             =>
        Left(
          s"Invalid IATA airport code: '$code'. Must be exactly 3 uppercase letters."
        )
    }
  }
}

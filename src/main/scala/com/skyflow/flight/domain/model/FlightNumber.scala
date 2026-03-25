package com.skyflow.flight.domain.model

/**
 * FlightNumber — Value Object with validation.
 * Format: 2 uppercase letters followed by 1-4 digits (e.g., VN123, AA1234).
 */
final case class FlightNumber(value: String) {
  override def toString: String = value
}

object FlightNumber {
  private val Pattern = "^[A-Z]{2}\\d{1,4}$".r

  def from(number: String): Either[String, FlightNumber] = {
    val trimmed = number.trim.toUpperCase
    trimmed match {
      case Pattern() => Right(FlightNumber(trimmed))
      case _         => Left(s"Invalid flight number: '$number'. Expected format: 2 letters + 1-4 digits (e.g., VN123).")
    }
  }
}

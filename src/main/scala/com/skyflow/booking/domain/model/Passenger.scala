package com.skyflow.booking.domain.model

/**
 * Passenger — Value Object representing passenger information.
 */
case class Passenger(
  passengerId: String,
  firstName: String,
  lastName: String,
  email: String
)

object Passenger {
  private val EmailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  def from(
    passengerId: String,
    firstName: String,
    lastName: String,
    email: String
  ): Either[String, Passenger] = {
    if (passengerId.trim.isEmpty) Left("Passenger ID cannot be empty")
    else if (firstName.trim.isEmpty) Left("First name cannot be empty")
    else if (lastName.trim.isEmpty) Left("Last name cannot be empty")
    else email match {
      case EmailPattern() => Right(Passenger(passengerId.trim, firstName.trim, lastName.trim, email.trim))
      case _              => Left(s"Invalid email format: $email")
    }
  }
}

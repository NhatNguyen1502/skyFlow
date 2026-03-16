package com.akka.learning.models.domain

import java.time.LocalDateTime

/**
 * Passenger & Booking Domain Models
 * 
 * Business domain for passenger information and booking requests.
 */

/**
 * Represents passenger information
 */
case class Passenger(
  passengerId: String,
  firstName: String,
  lastName: String,
  email: String
)

/**
 * Represents a booking request
 */
case class BookingRequest(
  requestId: String,
  passenger: Passenger,
  odPair: ODPair,
  requestedSeats: Int,
  requestTime: LocalDateTime
)

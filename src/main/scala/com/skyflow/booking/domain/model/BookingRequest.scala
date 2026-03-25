package com.skyflow.booking.domain.model

import java.time.LocalDateTime
import com.skyflow.allocation.domain.model.ODPair

/**
 * BookingRequest — Value Object representing a booking request.
 */
case class BookingRequest(
  requestId: String,
  passenger: Passenger,
  odPair: ODPair,
  requestedSeats: Int,
  requestTime: LocalDateTime
)

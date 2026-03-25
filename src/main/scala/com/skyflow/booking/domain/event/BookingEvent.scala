package com.skyflow.booking.domain.event

import java.time.LocalDateTime
import com.skyflow.booking.domain.model.BookingRequest
import com.skyflow.shared.infrastructure.serialization.CborSerializable

/**
 * BookingDomainEvent — Domain events for the Booking bounded context.
 */
sealed trait BookingDomainEvent extends CborSerializable {
  def bookingId: String
  def timestamp: LocalDateTime
}

case class BookingCreated(
  bookingId: String,
  request: BookingRequest,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends BookingDomainEvent

case class BookingConfirmed(
  bookingId: String,
  flightId: String,
  seatsBooked: Int,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends BookingDomainEvent

case class BookingCancelled(
  bookingId: String,
  reason: String,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends BookingDomainEvent

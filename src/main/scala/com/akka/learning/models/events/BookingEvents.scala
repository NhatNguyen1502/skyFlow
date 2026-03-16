package com.akka.learning.models.events

import java.time.LocalDateTime
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.BookingRequest

/**
 * Booking Events for Event Sourcing
 * 
 * Events representing the lifecycle of passenger bookings.
 */

/**
 * Base trait for all Booking events
 */
sealed trait BookingEvent extends CborSerializable {
  def bookingId: String
  def timestamp: LocalDateTime
}

/**
 * Event: A booking was created
 */
case class BookingCreated(
  bookingId: String,
  request: BookingRequest,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends BookingEvent

/**
 * Event: A booking was confirmed
 */
case class BookingConfirmed(
  bookingId: String,
  flightId: String,
  seatsBooked: Int,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends BookingEvent

/**
 * Event: A booking was cancelled
 */
case class BookingCancelled(
  bookingId: String,
  reason: String,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends BookingEvent

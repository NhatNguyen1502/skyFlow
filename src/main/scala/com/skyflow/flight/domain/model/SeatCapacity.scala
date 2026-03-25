package com.skyflow.flight.domain.model

/**
 * SeatCapacity — Value Object representing total seat count.
 * Must be between 1 and 853 (A380 max configuration).
 */
final case class SeatCapacity(value: Int) {
  override def toString: String = value.toString
}

object SeatCapacity {
  val MaxSeats = 853

  def from(seats: Int): Either[String, SeatCapacity] =
    if (seats > 0 && seats <= MaxSeats) Right(SeatCapacity(seats))
    else Left(s"Invalid seat capacity: $seats. Must be between 1 and $MaxSeats.")
}

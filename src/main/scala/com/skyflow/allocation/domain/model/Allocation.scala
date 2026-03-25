package com.skyflow.allocation.domain.model

/**
 * Allocation — Entity representing a single seat allocation on a flight.
 *
 * References the flight by ID only (no direct dependency on Flight aggregate).
 */
case class Allocation(
  odPair: ODPair,
  flightId: String,
  seatsAllocated: Int
)

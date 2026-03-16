package com.akka.learning.models.domain

import java.time.Duration

/**
 * Shared Domain Models
 * 
 * Common domain concepts used across multiple bounded contexts.
 */

/**
 * Origin-Destination pair for demand analysis
 */
case class ODPair(origin: Airport, destination: Airport) {
  def toKey: String = s"${origin.code}-${destination.code}"
}

/**
 * OTT (Origin-Terminal-Terminal) connection
 */
case class OTTConnection(
  origin: Airport,
  firstTerminal: Airport,
  secondTerminal: Airport,
  firstFlight: Flight,
  secondFlight: Flight,
  layoverDuration: Duration,
  totalDuration: Duration
) {
  def isValid: Boolean = {
    // Layover should be between 45 minutes and 6 hours
    val layoverMinutes = layoverDuration.toMinutes
    layoverMinutes >= 45 && layoverMinutes <= 360
  }
}

/**
 * Priority levels for allocation processing
 */
sealed trait Priority
object Priority {
  case object Low extends Priority
  case object Medium extends Priority
  case object High extends Priority
  case object Critical extends Priority
}

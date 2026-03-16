package com.akka.learning.models.events

import java.time.LocalDateTime
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.{AllocationRequest, AllocationResult, Allocation}

/**
 * Allocation Events for Event Sourcing
 * 
 * Events representing the lifecycle of allocation runs.
 */

/**
 * Base trait for all Allocation events
 */
sealed trait AllocationEvent extends CborSerializable {
  def allocationId: String
  def timestamp: LocalDateTime
}

/**
 * Event: An allocation run was started
 */
case class AllocationStarted(
  allocationId: String,
  runId: String,
  request: AllocationRequest,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends AllocationEvent

/**
 * Event: An individual seat allocation was made
 */
case class SeatAllocationMade(
  allocationId: String,
  runId: String,
  allocation: Allocation,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends AllocationEvent

/**
 * Event: An allocation run completed
 */
case class AllocationCompleted(
  allocationId: String,
  runId: String,
  result: AllocationResult,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends AllocationEvent

/**
 * Event: An allocation run failed
 */
case class AllocationFailed(
  allocationId: String,
  runId: String,
  reason: String,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends AllocationEvent

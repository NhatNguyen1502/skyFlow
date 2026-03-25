package com.skyflow.allocation.domain.event

import java.time.LocalDateTime
import com.skyflow.allocation.domain.model.{Allocation, AllocationRequest, AllocationResult}
import com.skyflow.shared.infrastructure.serialization.CborSerializable

/**
 * AllocationDomainEvent — Domain events for the Allocation aggregate.
 */
sealed trait AllocationDomainEvent extends CborSerializable {
  def allocationId: String
  def timestamp: LocalDateTime
}

case class AllocationRunStarted(
  allocationId: String,
  runId: String,
  request: AllocationRequest,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends AllocationDomainEvent

case class SeatAllocationMade(
  allocationId: String,
  runId: String,
  allocation: Allocation,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends AllocationDomainEvent

case class AllocationRunCompleted(
  allocationId: String,
  runId: String,
  result: AllocationResult,
  timestamp: LocalDateTime = LocalDateTime.now()
) extends AllocationDomainEvent

package com.skyflow.allocation.domain.error

/**
 * AllocationError — Domain errors for the Allocation bounded context.
 */
sealed trait AllocationError {
  def message: String
}

object AllocationError {
  case class ValidationFailed(message: String) extends AllocationError
  case class AllocationNotFound(allocationId: String) extends AllocationError {
    val message: String = s"Allocation $allocationId not found"
  }
  case class AllocationAlreadyExists(allocationId: String) extends AllocationError {
    val message: String = s"Allocation $allocationId already exists"
  }
  case class ProcessingFailed(allocationId: String, reason: String) extends AllocationError {
    val message: String = s"Allocation $allocationId processing failed: $reason"
  }
  case class NoFlightsAvailable(odPairKey: String) extends AllocationError {
    val message: String = s"No flights available for O&D pair: $odPairKey"
  }
}

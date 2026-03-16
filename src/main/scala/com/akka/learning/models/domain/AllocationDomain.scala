package com.akka.learning.models.domain

import java.time.LocalDateTime

/**
 * Allocation Domain Models
 * 
 * Business domain for allocation runs, demand analysis, and capacity optimization.
 */

/**
 * Allocation request for batch processing
 */
case class AllocationRequest(
  allocationId: String,
  runId: String,
  odPairs: List[(ODPair, Int)], // ODPair with demand count
  priority: Priority,
  requestTime: LocalDateTime
)

/**
 * Result of an allocation run
 */
case class AllocationResult(
  allocationId: String,
  runId: String,
  allocations: List[Allocation],
  unallocated: List[(ODPair, Int)],
  processingTimeMs: Long,
  completedAt: LocalDateTime
)

/**
 * Individual allocation of seats on a flight
 */
case class Allocation(
  odPair: ODPair,
  flight: Flight,
  seatsAllocated: Int
)

/**
 * O&D demand statistics
 */
case class ODDemand(
  odPair: ODPair,
  totalRequests: Int,
  totalSeatsRequested: Int,
  averageSeatsPerRequest: Double,
  periodStart: LocalDateTime,
  periodEnd: LocalDateTime
)

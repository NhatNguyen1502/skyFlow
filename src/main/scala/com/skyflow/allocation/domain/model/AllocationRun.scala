package com.skyflow.allocation.domain.model

import java.time.LocalDateTime

/**
 * AllocationRequest — Command object for starting an allocation run.
 */
case class AllocationRequest(
  allocationId: String,
  runId: String,
  odPairs: List[(ODPair, Int)],
  priority: Priority,
  requestTime: LocalDateTime
)

/**
 * AllocationResult — Outcome of an allocation run.
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
 * AllocationRun — Tracks an active allocation run.
 */
case class AllocationRun(
  request: AllocationRequest,
  startTime: LocalDateTime,
  progress: Double = 0.0
)

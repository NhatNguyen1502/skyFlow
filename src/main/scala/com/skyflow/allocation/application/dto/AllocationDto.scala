package com.skyflow.allocation.application.dto

import com.skyflow.allocation.domain.model.Priority

/**
 * DTOs for the Allocation bounded context HTTP API.
 */
case class AllocationRequestHttp(
  runId: String,
  odPairs: List[ODPairDemand],
  priority: Priority
)

case class ODPairDemand(
  originCode: String,
  destinationCode: String,
  demand: Int
)

case class AllocationHttpResponse(
  allocationId: String,
  status: String,
  message: String
)

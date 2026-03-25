package com.skyflow.allocation.application.service

import com.skyflow.allocation.application.dto.{AllocationRequestHttp, ODPairDemand}
import com.skyflow.allocation.domain.error.AllocationError
import com.skyflow.allocation.domain.model.{AllocationRequest, ODPair}
import java.time.LocalDateTime
import java.util.UUID

/**
 * AllocationApplicationService — Orchestration layer for the Allocation context.
 *
 * Converts DTOs to domain objects and delegates to domain services.
 */
object AllocationApplicationService {

  /**
   * Convert an HTTP allocation request to a domain AllocationRequest.
   */
  def createAllocationRequest(
    httpRequest: AllocationRequestHttp
  ): Either[AllocationError, AllocationRequest] = {
    val odPairsResult = httpRequest.odPairs.foldLeft[Either[AllocationError, List[(ODPair, Int)]]](Right(Nil)) {
      case (Left(err), _) => Left(err)
      case (Right(acc), demand) =>
        createODPair(demand).map(odPair => acc :+ (odPair, demand.demand))
    }

    odPairsResult.map { odPairs =>
      AllocationRequest(
        allocationId = UUID.randomUUID().toString,
        runId = httpRequest.runId,
        odPairs = odPairs,
        priority = httpRequest.priority,
        requestTime = LocalDateTime.now()
      )
    }
  }

  private def createODPair(demand: ODPairDemand): Either[AllocationError, ODPair] =
    ODPair.from(demand.originCode, demand.destinationCode)
      .left.map(AllocationError.ValidationFailed)
}

package com.skyflow.allocation.domain.model

import java.time.LocalDateTime

/**
 * ODDemand — Value Object for O&D demand statistics.
 */
case class ODDemand(
  odPair: ODPair,
  totalRequests: Int,
  totalSeatsRequested: Int,
  averageSeatsPerRequest: Double,
  periodStart: LocalDateTime,
  periodEnd: LocalDateTime
)

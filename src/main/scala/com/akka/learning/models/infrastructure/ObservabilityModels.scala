package com.akka.learning.models.infrastructure

import java.time.LocalDateTime

/**
 * Observability & Infrastructure Models
 * 
 * Technical cross-cutting concerns for monitoring, tracing, and performance tracking.
 * These are NOT business domain models.
 */

/**
 * Performance metrics for tracking
 */
case class PerformanceMetrics(
  operation: String,
  durationMs: Long,
  timestamp: LocalDateTime,
  correlationId: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)

/**
 * Correlation context for request tracing
 */
case class CorrelationContext(
  correlationId: String,
  runId: Option[String] = None,
  odGroup: Option[String] = None,
  startTime: LocalDateTime = LocalDateTime.now()
) {
  def withRunId(id: String): CorrelationContext = copy(runId = Some(id))
  def withODGroup(group: String): CorrelationContext = copy(odGroup = Some(group))
}

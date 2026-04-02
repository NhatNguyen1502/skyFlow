package com.skyflow.shared.infrastructure.observability

import java.time.LocalDateTime

/** Performance metrics for tracking operations. */
case class PerformanceMetrics(
    operation: String,
    durationMs: Long,
    timestamp: LocalDateTime,
    correlationId: Option[String] = None,
    metadata: Map[String, String] = Map.empty
)

/** Correlation context for request tracing across async boundaries. */
case class CorrelationContext(
    correlationId: String,
    runId: Option[String] = None,
    odGroup: Option[String] = None,
    startTime: LocalDateTime = LocalDateTime.now()
) {
  def withRunId(id: String): CorrelationContext = copy(runId = Some(id))
  def withODGroup(group: String): CorrelationContext =
    copy(odGroup = Some(group))
}

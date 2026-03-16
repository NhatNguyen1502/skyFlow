package com.akka.learning.utils

import org.slf4j.Logger
import com.akka.learning.models.infrastructure.{PerformanceMetrics, CorrelationContext}
import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Performance metrics and benchmarking utilities.
 * 
 * Provides tools for measuring execution time, tracking performance,
 * and collecting metrics for APIs and pipelines.
 * Follows Single Responsibility Principle - only handles performance tracking.
 * 
 * @see [[com.akka.learning.utils.CorrelationId]] for correlation tracking
 */
object Metrics {
  
  /**
   * Measure execution time of a synchronous block
   * 
   * @param operation Name of the operation being measured
   * @param block Code block to execute and measure
   * @param logger Logger for recording metrics
   * @return Tuple of (result, duration in milliseconds)
   */
  def time[T](operation: String)(block: => T)(implicit logger: Logger): (T, Long) = {
    val start = System.currentTimeMillis()
    val result = block
    val duration = System.currentTimeMillis() - start
    
    logger.info(s"Operation '$operation' completed in ${duration}ms")
    (result, duration)
  }

  /**
   * Measure execution time of an asynchronous Future
   * 
   * @param operation Name of the operation being measured
   * @param future Future to execute and measure
   * @param ec ExecutionContext for Future execution
   * @param logger Logger for recording metrics
   * @return Future of tuple (result, duration in milliseconds)
   */
  def timeAsync[T](operation: String)(future: => Future[T])
                  (implicit ec: ExecutionContext, logger: Logger): Future[(T, Long)] = {
    val start = System.currentTimeMillis()
    future.map { result =>
      val duration = System.currentTimeMillis() - start
      logger.info(s"Async operation '$operation' completed in ${duration}ms")
      (result, duration)
    }
  }

  /**
   * Measure execution time and create a PerformanceMetrics object
   * 
   * @param operation Name of the operation
   * @param block Code block to execute
   * @param correlationId Optional correlation ID
   * @param metadata Additional metadata to include
   * @return Tuple of (result, PerformanceMetrics)
   */
  def measure[T](operation: String, correlationId: Option[String] = None, 
                 metadata: Map[String, String] = Map.empty)
                (block: => T): (T, PerformanceMetrics) = {
    val start = System.currentTimeMillis()
    val result = block
    val duration = System.currentTimeMillis() - start
    
    val metrics = PerformanceMetrics(
      operation = operation,
      durationMs = duration,
      timestamp = LocalDateTime.now(),
      correlationId = correlationId,
      metadata = metadata
    )
    
    (result, metrics)
  }

  /**
   * Measure async execution and create PerformanceMetrics
   * 
   * @param operation Name of the operation
   * @param correlationId Optional correlation ID
   * @param metadata Additional metadata
   * @param future Future to execute
   * @param ec ExecutionContext for Future execution
   * @return Future of tuple (result, PerformanceMetrics)
   */
  def measureAsync[T](operation: String, correlationId: Option[String] = None,
                      metadata: Map[String, String] = Map.empty)
                     (future: => Future[T])
                     (implicit ec: ExecutionContext): Future[(T, PerformanceMetrics)] = {
    val start = System.currentTimeMillis()
    future.map { result =>
      val duration = System.currentTimeMillis() - start
      val metrics = PerformanceMetrics(
        operation = operation,
        durationMs = duration,
        timestamp = LocalDateTime.now(),
        correlationId = correlationId,
        metadata = metadata
      )
      (result, metrics)
    }
  }

  /**
   * Create a performance tracker for multi-checkpoint operations
   * 
   * @param operation Name of the operation being tracked
   * @param logger Logger for recording checkpoints
   * @return PerformanceTracker instance
   */
  def track(operation: String)(implicit logger: Logger): PerformanceTracker = {
    new PerformanceTracker(operation)
  }

  /**
   * Performance tracker for operations with multiple checkpoints.
   * Useful for tracking long-running pipelines or complex operations.
   * 
   * Example:
   * {{{
   * val tracker = Metrics.track("allocation-pipeline")
   * tracker.checkpoint("load-data")
   * // ... process data ...
   * tracker.checkpoint("validate")
   * // ... validate ...
   * tracker.complete()
   * }}}
   */
  class PerformanceTracker(operation: String)(implicit logger: Logger) {
    private val startTime = System.currentTimeMillis()
    private var checkpoints: List[(String, Long)] = List.empty
    private var metadata: Map[String, String] = Map.empty

    /**
     * Record a checkpoint with the elapsed time
     * @param label Checkpoint label
     */
    def checkpoint(label: String): Unit = {
      val elapsed = System.currentTimeMillis() - startTime
      checkpoints = checkpoints :+ (label, elapsed)
      logger.debug(s"[$operation] Checkpoint '$label': ${elapsed}ms")
    }

    /**
     * Add metadata to the tracker
     * @param key Metadata key
     * @param value Metadata value
     */
    def addMetadata(key: String, value: String): Unit = {
      metadata = metadata + (key -> value)
    }

    /**
     * Complete the tracking and log summary
     * @return Total duration in milliseconds
     */
    def complete(): Long = {
      val totalTime = System.currentTimeMillis() - startTime
      logger.info(s"[$operation] Completed in ${totalTime}ms with ${checkpoints.size} checkpoints")
      
      if (checkpoints.nonEmpty) {
        checkpoints.foreach { case (label, time) =>
          logger.debug(s"  - $label: ${time}ms")
        }
      }
      
      if (metadata.nonEmpty) {
        logger.debug(s"  Metadata: ${metadata.mkString(", ")}")
      }
      
      totalTime
    }

    /**
     * Get all checkpoints
     * @return List of (label, elapsed time) tuples
     */
    def getCheckpoints: List[(String, Long)] = checkpoints

    /**
     * Get current elapsed time without completing
     * @return Elapsed time in milliseconds
     */
    def elapsed: Long = System.currentTimeMillis() - startTime

    /**
     * Create a PerformanceMetrics object from current state
     * @param correlationId Optional correlation ID
     * @return PerformanceMetrics object
     */
    def toMetrics(correlationId: Option[String] = None): PerformanceMetrics = {
      val checkpointMetadata = checkpoints.zipWithIndex.map { case ((label, time), idx) =>
        s"checkpoint_${idx}_$label" -> s"${time}ms"
      }.toMap
      
      PerformanceMetrics(
        operation = operation,
        durationMs = elapsed,
        timestamp = LocalDateTime.now(),
        correlationId = correlationId,
        metadata = metadata ++ checkpointMetadata
      )
    }
  }

  /**
   * Statistical aggregator for collecting metrics over time
   */
  class MetricsAggregator(operation: String) {
    private var count: Long = 0
    private var totalDuration: Long = 0
    private var minDuration: Long = Long.MaxValue
    private var maxDuration: Long = 0

    /**
     * Record a new metric
     * @param duration Duration in milliseconds
     */
    def record(duration: Long): Unit = synchronized {
      count += 1
      totalDuration += duration
      minDuration = math.min(minDuration, duration)
      maxDuration = math.max(maxDuration, duration)
    }

    /**
     * Get average duration
     * @return Average duration in milliseconds, or 0 if no records
     */
    def average: Double = if (count > 0) totalDuration.toDouble / count else 0.0

    /**
     * Get minimum duration
     * @return Minimum duration, or 0 if no records
     */
    def min: Long = if (count > 0) minDuration else 0

    /**
     * Get maximum duration
     * @return Maximum duration, or 0 if no records
     */
    def max: Long = if (count > 0) maxDuration else 0

    /**
     * Get total count of records
     * @return Number of recorded metrics
     */
    def getCount: Long = count

    /**
     * Get statistics summary
     * @return Map of statistics
     */
    def summary: Map[String, Any] = Map(
      "operation" -> operation,
      "count" -> count,
      "avg_ms" -> f"$average%.2f",
      "min_ms" -> min,
      "max_ms" -> max,
      "total_ms" -> totalDuration
    )

    /**
     * Reset all statistics
     */
    def reset(): Unit = synchronized {
      count = 0
      totalDuration = 0
      minDuration = Long.MaxValue
      maxDuration = 0
    }
  }

  /**
   * Create a metrics aggregator
   * @param operation Name of the operation
   * @return MetricsAggregator instance
   */
  def aggregator(operation: String): MetricsAggregator = {
    new MetricsAggregator(operation)
  }
}

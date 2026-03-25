package com.skyflow.shared.infrastructure.observability

import org.slf4j.Logger
import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Performance metrics and benchmarking utilities.
 */
object Metrics {

  def time[T](operation: String)(block: => T)(implicit logger: Logger): (T, Long) = {
    val start = System.currentTimeMillis()
    val result = block
    val duration = System.currentTimeMillis() - start
    logger.info(s"Operation '$operation' completed in ${duration}ms")
    (result, duration)
  }

  def timeAsync[T](operation: String)(future: => Future[T])
                  (implicit ec: ExecutionContext, logger: Logger): Future[(T, Long)] = {
    val start = System.currentTimeMillis()
    future.map { result =>
      val duration = System.currentTimeMillis() - start
      logger.info(s"Async operation '$operation' completed in ${duration}ms")
      (result, duration)
    }
  }

  def measure[T](operation: String, correlationId: Option[String] = None,
                 metadata: Map[String, String] = Map.empty)
                (block: => T): (T, PerformanceMetrics) = {
    val start = System.currentTimeMillis()
    val result = block
    val duration = System.currentTimeMillis() - start
    val metrics = PerformanceMetrics(operation, duration, LocalDateTime.now(), correlationId, metadata)
    (result, metrics)
  }

  def measureAsync[T](operation: String, correlationId: Option[String] = None,
                      metadata: Map[String, String] = Map.empty)
                     (future: => Future[T])
                     (implicit ec: ExecutionContext): Future[(T, PerformanceMetrics)] = {
    val start = System.currentTimeMillis()
    future.map { result =>
      val duration = System.currentTimeMillis() - start
      val metrics = PerformanceMetrics(operation, duration, LocalDateTime.now(), correlationId, metadata)
      (result, metrics)
    }
  }

  def track(operation: String)(implicit logger: Logger): PerformanceTracker =
    new PerformanceTracker(operation)

  class PerformanceTracker(operation: String)(implicit logger: Logger) {
    private val startTime = System.currentTimeMillis()
    private var checkpoints: List[(String, Long)] = List.empty
    private var _metadata: Map[String, String] = Map.empty

    def checkpoint(label: String): Unit = {
      val el = System.currentTimeMillis() - startTime
      checkpoints = checkpoints :+ (label, el)
      logger.debug(s"[$operation] Checkpoint '$label': ${el}ms")
    }

    def addMetadata(key: String, value: String): Unit =
      _metadata = _metadata + (key -> value)

    def complete(): Long = {
      val totalTime = System.currentTimeMillis() - startTime
      logger.info(s"[$operation] Completed in ${totalTime}ms with ${checkpoints.size} checkpoints")
      checkpoints.foreach { case (label, time) => logger.debug(s"  - $label: ${time}ms") }
      if (_metadata.nonEmpty) logger.debug(s"  Metadata: ${_metadata.mkString(", ")}")
      totalTime
    }

    def getCheckpoints: List[(String, Long)] = checkpoints
    def elapsed: Long = System.currentTimeMillis() - startTime

    def toMetrics(correlationId: Option[String] = None): PerformanceMetrics = {
      val checkpointMetadata = checkpoints.zipWithIndex.map { case ((label, time), idx) =>
        s"checkpoint_${idx}_$label" -> s"${time}ms"
      }.toMap
      PerformanceMetrics(operation, elapsed, LocalDateTime.now(), correlationId, _metadata ++ checkpointMetadata)
    }
  }

  class MetricsAggregator(operation: String) {
    private var count: Long = 0
    private var totalDuration: Long = 0
    private var minDuration: Long = Long.MaxValue
    private var maxDuration: Long = 0

    def record(duration: Long): Unit = synchronized {
      count += 1
      totalDuration += duration
      minDuration = math.min(minDuration, duration)
      maxDuration = math.max(maxDuration, duration)
    }

    def average: Double = if (count > 0) totalDuration.toDouble / count else 0.0
    def min: Long = if (count > 0) minDuration else 0
    def max: Long = if (count > 0) maxDuration else 0
    def total: Long = totalDuration
    def getCount: Long = count

    def summary(implicit logger: Logger): Unit =
      logger.info(s"[$operation] count=$count avg=${f"$average%.1f"}ms min=${min}ms max=${max}ms total=${total}ms")
  }

  def aggregator(operation: String): MetricsAggregator = new MetricsAggregator(operation)
}

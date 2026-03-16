package com.akka.learning.utils

import org.slf4j.MDC
import com.akka.learning.models.infrastructure.CorrelationContext
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}
import java.util.UUID

/**
 * Correlation ID management for request tracing across async boundaries.
 * 
 * This utility manages correlation context (correlationId, runId, odGroup) using SLF4J's MDC.
 * Follows Single Responsibility Principle - only handles correlation tracking.
 * 
 * @see [[com.akka.learning.utils.Metrics]] for performance tracking
 * @see [[com.akka.learning.utils.Logging]] for enhanced logging
 */
object CorrelationId {
  
  // MDC keys for context propagation
  val CORRELATION_ID_KEY = "correlationId"
  val RUN_ID_KEY = "runId"
  val OD_GROUP_KEY = "odGroup"

  /**
   * Generate a new unique correlation ID
   * @return UUID-based correlation ID
   */
  def generate(): String = UUID.randomUUID().toString

  /**
   * Get current correlation ID from MDC
   * @return Some(correlationId) if present, None otherwise
   */
  def get(): Option[String] = Option(MDC.get(CORRELATION_ID_KEY))

  /**
   * Get current run ID from MDC
   * @return Some(runId) if present, None otherwise
   */
  def getRunId(): Option[String] = Option(MDC.get(RUN_ID_KEY))

  /**
   * Get current O&D group from MDC
   * @return Some(odGroup) if present, None otherwise
   */
  def getODGroup(): Option[String] = Option(MDC.get(OD_GROUP_KEY))

  /**
   * Get complete correlation context from MDC.
   * If no correlationId exists, generates a new one.
   * 
   * @return CorrelationContext with current or new correlationId
   */
  def getContext(): CorrelationContext = {
    val correlationId = Option(MDC.get(CORRELATION_ID_KEY)).getOrElse(generate())
    val runId = Option(MDC.get(RUN_ID_KEY))
    val odGroup = Option(MDC.get(OD_GROUP_KEY))
    
    CorrelationContext(correlationId, runId, odGroup)
  }

  /**
   * Set correlation context in MDC for the current thread
   * @param context CorrelationContext to set
   */
  def setContext(context: CorrelationContext): Unit = {
    MDC.put(CORRELATION_ID_KEY, context.correlationId)
    context.runId.foreach(MDC.put(RUN_ID_KEY, _))
    context.odGroup.foreach(MDC.put(OD_GROUP_KEY, _))
  }

  /**
   * Set only correlation ID in MDC
   * @param id Correlation ID to set
   */
  def set(id: String): Unit = {
    MDC.put(CORRELATION_ID_KEY, id)
  }

  /**
   * Set run ID in MDC
   * @param runId Run ID to set
   */
  def setRunId(runId: String): Unit = {
    MDC.put(RUN_ID_KEY, runId)
  }

  /**
   * Set O&D group in MDC
   * @param odGroup O&D group identifier
   */
  def setODGroup(odGroup: String): Unit = {
    MDC.put(OD_GROUP_KEY, odGroup)
  }

  /**
   * Clear all correlation context from MDC
   */
  def clear(): Unit = {
    MDC.remove(CORRELATION_ID_KEY)
    MDC.remove(RUN_ID_KEY)
    MDC.remove(OD_GROUP_KEY)
  }

  /**
   * Execute a block of code with a specific correlation context.
   * Restores the previous context after execution.
   * 
   * @param context CorrelationContext to use during execution
   * @param block Code block to execute
   * @return Result of the block execution
   */
  def withContext[T](context: CorrelationContext)(block: => T): T = {
    val previousContext = getContext()
    try {
      setContext(context)
      block
    } finally {
      setContext(previousContext)
    }
  }

  /**
   * Execute a Future with correlation context propagation.
   * The context is restored in the Future's callbacks.
   * 
   * @param context CorrelationContext to propagate
   * @param future Future to execute
   * @param ec ExecutionContext for Future execution
   * @return Future with context propagation
   */
  def withContextAsync[T](context: CorrelationContext)(future: => Future[T])
                         (implicit ec: ExecutionContext): Future[T] = {
    val ctx = context
    future.andThen {
      case Success(_) => setContext(ctx)
      case Failure(_) => setContext(ctx)
    }
  }

  /**
   * Execute a block with a new generated correlation ID
   * @param block Code block to execute
   * @return Result of the block execution
   */
  def withNewContext[T](block: => T): T = {
    withContext(CorrelationContext(generate()))(block)
  }
}

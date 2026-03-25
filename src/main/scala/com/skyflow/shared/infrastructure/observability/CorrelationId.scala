package com.skyflow.shared.infrastructure.observability

import org.slf4j.MDC
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}
import java.util.UUID

/**
 * Correlation ID management for request tracing across async boundaries.
 */
object CorrelationId {

  val CORRELATION_ID_KEY = "correlationId"
  val RUN_ID_KEY = "runId"
  val OD_GROUP_KEY = "odGroup"

  def generate(): String = UUID.randomUUID().toString

  def get(): Option[String] = Option(MDC.get(CORRELATION_ID_KEY))
  def getRunId(): Option[String] = Option(MDC.get(RUN_ID_KEY))
  def getODGroup(): Option[String] = Option(MDC.get(OD_GROUP_KEY))

  def getContext(): CorrelationContext = {
    val correlationId = Option(MDC.get(CORRELATION_ID_KEY)).getOrElse(generate())
    val runId = Option(MDC.get(RUN_ID_KEY))
    val odGroup = Option(MDC.get(OD_GROUP_KEY))
    CorrelationContext(correlationId, runId, odGroup)
  }

  def setContext(context: CorrelationContext): Unit = {
    MDC.put(CORRELATION_ID_KEY, context.correlationId)
    context.runId.foreach(MDC.put(RUN_ID_KEY, _))
    context.odGroup.foreach(MDC.put(OD_GROUP_KEY, _))
  }

  def set(id: String): Unit = MDC.put(CORRELATION_ID_KEY, id)
  def setRunId(runId: String): Unit = MDC.put(RUN_ID_KEY, runId)
  def setODGroup(odGroup: String): Unit = MDC.put(OD_GROUP_KEY, odGroup)

  def clear(): Unit = {
    MDC.remove(CORRELATION_ID_KEY)
    MDC.remove(RUN_ID_KEY)
    MDC.remove(OD_GROUP_KEY)
  }

  def withContext[T](context: CorrelationContext)(block: => T): T = {
    val previousContext = getContext()
    try {
      setContext(context)
      block
    } finally {
      setContext(previousContext)
    }
  }

  def withContextAsync[T](context: CorrelationContext)(future: => Future[T])
                         (implicit ec: ExecutionContext): Future[T] = {
    val ctx = context
    future.andThen {
      case Success(_) => setContext(ctx)
      case Failure(_) => setContext(ctx)
    }
  }

  def withNewContext[T](block: => T): T = {
    withContext(CorrelationContext(generate()))(block)
  }
}

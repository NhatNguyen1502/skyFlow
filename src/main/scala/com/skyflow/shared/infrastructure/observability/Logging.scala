package com.skyflow.shared.infrastructure.observability

import org.slf4j.Logger

/**
 * Enhanced logging utilities with correlation context support.
 */
object Logging {

  implicit class RichLogger(logger: Logger) {

    def debugWithContext(message: String)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.debug(message))

    def debugWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.debug(message, args.map(_.asInstanceOf[AnyRef]): _*))

    def infoWithContext(message: String)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.info(message))

    def infoWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.info(message, args.map(_.asInstanceOf[AnyRef]): _*))

    def warnWithContext(message: String)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.warn(message))

    def warnWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.warn(message, args.map(_.asInstanceOf[AnyRef]): _*))

    def errorWithContext(message: String)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.error(message))

    def errorWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.error(message, args.map(_.asInstanceOf[AnyRef]): _*))

    def errorWithContext(message: String, throwable: Throwable)(implicit context: CorrelationContext): Unit =
      CorrelationId.withContext(context)(logger.error(message, throwable))
  }

  object StructuredLogging {
    def format(message: String, fields: Map[String, Any]): String = {
      val fieldsStr = fields.map { case (k, v) => s"$k=$v" }.mkString(", ")
      s"$message | $fieldsStr"
    }

    def info(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
            (implicit context: CorrelationContext): Unit =
      logger.infoWithContext(format(message, fields))

    def debug(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
             (implicit context: CorrelationContext): Unit =
      logger.debugWithContext(format(message, fields))

    def warn(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
            (implicit context: CorrelationContext): Unit =
      logger.warnWithContext(format(message, fields))

    def error(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
             (implicit context: CorrelationContext): Unit =
      logger.errorWithContext(format(message, fields))
  }
}

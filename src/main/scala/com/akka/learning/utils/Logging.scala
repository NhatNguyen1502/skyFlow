package com.akka.learning.utils

import org.slf4j.Logger
import com.akka.learning.models.infrastructure.CorrelationContext

/**
 * Enhanced logging utilities with context support.
 * 
 * Provides extension methods for SLF4J Logger to automatically
 * include correlation context in log statements.
 * Follows Single Responsibility Principle - only handles logging enhancements.
 * 
 * Usage:
 * {{{
 * import com.akka.learning.utils.Logging._
 * 
 * implicit val context = CorrelationContext(correlationId = "123")
 * logger.infoWithContext("Processing request")
 * }}}
 * 
 * @see [[com.akka.learning.utils.CorrelationId]] for correlation tracking
 */
object Logging {
  
  /**
   * Extension methods for SLF4J Logger with correlation context support
   */
  implicit class RichLogger(logger: Logger) {
    
    /**
     * Log debug message with correlation context
     * @param message Log message
     * @param context Implicit correlation context
     */
    def debugWithContext(message: String)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.debug(message)
      }
    }

    /**
     * Log debug message with arguments and correlation context
     * @param message Log message with placeholders
     * @param args Arguments for placeholders
     * @param context Implicit correlation context
     */
    def debugWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.debug(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
    
    /**
     * Log info message with correlation context
     * @param message Log message
     * @param context Implicit correlation context
     */
    def infoWithContext(message: String)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.info(message)
      }
    }

    /**
     * Log info message with arguments and correlation context
     * @param message Log message with placeholders
     * @param args Arguments for placeholders
     * @param context Implicit correlation context
     */
    def infoWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.info(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
    
    /**
     * Log warning message with correlation context
     * @param message Log message
     * @param context Implicit correlation context
     */
    def warnWithContext(message: String)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.warn(message)
      }
    }

    /**
     * Log warning message with arguments and correlation context
     * @param message Log message with placeholders
     * @param args Arguments for placeholders
     * @param context Implicit correlation context
     */
    def warnWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.warn(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
    
    /**
     * Log error message with correlation context
     * @param message Log message
     * @param context Implicit correlation context
     */
    def errorWithContext(message: String)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.error(message)
      }
    }

    /**
     * Log error message with arguments and correlation context
     * @param message Log message with placeholders
     * @param args Arguments for placeholders
     * @param context Implicit correlation context
     */
    def errorWithContext(message: String, args: Any*)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.error(message, args.map(_.asInstanceOf[AnyRef]): _*)
      }
    }
    
    /**
     * Log error message with exception and correlation context
     * @param message Log message
     * @param throwable Exception to log
     * @param context Implicit correlation context
     */
    def errorWithContext(message: String, throwable: Throwable)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        logger.error(message, throwable)
      }
    }

    /**
     * Log message with custom level and correlation context
     * @param level Log level (DEBUG, INFO, WARN, ERROR)
     * @param message Log message
     * @param context Implicit correlation context
     */
    def logWithContext(level: String, message: String)(implicit context: CorrelationContext): Unit = {
      CorrelationId.withContext(context) {
        level.toUpperCase match {
          case "DEBUG" => logger.debug(message)
          case "INFO" => logger.info(message)
          case "WARN" => logger.warn(message)
          case "ERROR" => logger.error(message)
          case _ => logger.info(message)
        }
      }
    }
  }

  /**
   * Structured logging helpers for consistent log formatting
   */
  object StructuredLogging {
    
    /**
     * Create a structured log message with key-value pairs
     * @param message Base message
     * @param fields Key-value pairs to include
     * @return Formatted message
     */
    def format(message: String, fields: Map[String, Any]): String = {
      val fieldsStr = fields.map { case (k, v) => s"$k=$v" }.mkString(", ")
      s"$message | $fieldsStr"
    }

    /**
     * Log structured info message
     * @param logger Logger instance
     * @param message Base message
     * @param fields Key-value pairs
     * @param context Implicit correlation context
     */
    def info(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
            (implicit context: CorrelationContext): Unit = {
      logger.infoWithContext(format(message, fields))
    }

    /**
     * Log structured debug message
     * @param logger Logger instance
     * @param message Base message
     * @param fields Key-value pairs
     * @param context Implicit correlation context
     */
    def debug(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
             (implicit context: CorrelationContext): Unit = {
      logger.debugWithContext(format(message, fields))
    }

    /**
     * Log structured warning message
     * @param logger Logger instance
     * @param message Base message
     * @param fields Key-value pairs
     * @param context Implicit correlation context
     */
    def warn(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
            (implicit context: CorrelationContext): Unit = {
      logger.warnWithContext(format(message, fields))
    }

    /**
     * Log structured error message
     * @param logger Logger instance
     * @param message Base message
     * @param fields Key-value pairs
     * @param context Implicit correlation context
     */
    def error(logger: Logger, message: String, fields: Map[String, Any] = Map.empty)
             (implicit context: CorrelationContext): Unit = {
      logger.errorWithContext(format(message, fields))
    }
  }
}

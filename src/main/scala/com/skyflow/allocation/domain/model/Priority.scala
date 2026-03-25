package com.skyflow.allocation.domain.model

/**
 * Priority — Enum Value Object for allocation processing priority.
 */
sealed trait Priority

object Priority {
  case object Low extends Priority
  case object Medium extends Priority
  case object High extends Priority
  case object Critical extends Priority

  def from(priority: String): Either[String, Priority] = priority match {
    case "Low"      => Right(Low)
    case "Medium"   => Right(Medium)
    case "High"     => Right(High)
    case "Critical" => Right(Critical)
    case other      => Left(s"Unknown priority: '$other'")
  }
}

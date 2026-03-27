package com.skyflow.streaming.domain.model

import java.time.Instant

/**
 * Domain models cho Akka Streams processing pipeline.
 * Dùng chung cho tất cả streaming features.
 */

/** Một dòng booking từ CSV đã được parse */
final case class BookingRecord(
  passengerId: String,
  passengerName: String,
  email: String,
  origin: String,
  destination: String,
  seatsRequested: Int
) {
  def routeKey: String = s"${origin}_$destination"

  def toCsvLine: String =
    s"$passengerId,$passengerName,$email,$origin,$destination,$seatsRequested"
}

object BookingRecord {
  val CsvHeader: String = "passengerId,passengerName,email,origin,destination,seatsRequested"
}

/** Kết quả validate — booking hợp lệ */
final case class ValidatedBooking(
  record: BookingRecord,
  validatedAt: Instant
) {
  def toCsvLine: String = record.toCsvLine
}

/** Kết quả xử lý 1 record */
sealed trait ProcessingOutcome
final case class ProcessingSuccess(record: BookingRecord, message: String) extends ProcessingOutcome
final case class ProcessingFailure(record: BookingRecord, reason: String) extends ProcessingOutcome

/** Báo cáo tổng hợp sau khi xử lý stream */
final case class ProcessingReport(
  totalRecords: Int,
  successCount: Int,
  errorCount: Int,
  startTime: Instant,
  endTime: Option[Instant],
  errors: List[String]
) {
  def durationMs: Long = endTime.map(e => e.toEpochMilli - startTime.toEpochMilli).getOrElse(0L)

  def merge(other: ProcessingReport): ProcessingReport = copy(
    totalRecords = totalRecords + other.totalRecords,
    successCount = successCount + other.successCount,
    errorCount = errorCount + other.errorCount,
    errors = errors ++ other.errors
  )

  def addSuccess: ProcessingReport = copy(
    totalRecords = totalRecords + 1,
    successCount = successCount + 1
  )

  def addError(error: String): ProcessingReport = copy(
    totalRecords = totalRecords + 1,
    errorCount = errorCount + 1,
    errors = errors :+ error
  )

  def complete: ProcessingReport = copy(endTime = Some(Instant.now()))
}

object ProcessingReport {
  def empty: ProcessingReport = ProcessingReport(
    totalRecords = 0,
    successCount = 0,
    errorCount = 0,
    startTime = Instant.now(),
    endTime = None,
    errors = Nil
  )
}

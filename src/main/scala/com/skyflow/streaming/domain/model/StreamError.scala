package com.skyflow.streaming.domain.model

/** Các loại lỗi trong stream processing */
sealed trait StreamError {
  def message: String
  def line: Option[String]
}

/** Lỗi parse CSV — dòng không đúng format */
final case class CsvParseError(
  rawLine: String,
  reason: String
) extends StreamError {
  override def message: String = s"Parse error: $reason"
  override def line: Option[String] = Some(rawLine)
}

/** Lỗi validation — dữ liệu không hợp lệ */
final case class ValidationError(
  record: BookingRecord,
  violations: List[String]
) extends StreamError {
  override def message: String = s"Validation failed: ${violations.mkString(", ")}"
  override def line: Option[String] = Some(record.toCsvLine)
}

/** Lỗi khi giao tiếp với Actor */
final case class ActorCommunicationError(
  reason: String
) extends StreamError {
  override def message: String = s"Actor error: $reason"
  override def line: Option[String] = None
}

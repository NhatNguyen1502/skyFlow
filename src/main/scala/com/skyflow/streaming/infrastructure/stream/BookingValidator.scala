package com.skyflow.streaming.infrastructure.stream

import com.skyflow.streaming.domain.model.{BookingRecord, ValidationError}

/** Validation rules cho BookingRecord */
object BookingValidator {

  /** Validate 1 BookingRecord. Trả về Right nếu hợp lệ, Left nếu có lỗi. */
  def validate(record: BookingRecord): Either[ValidationError, BookingRecord] = {
    val violations = List.newBuilder[String]

    if (record.passengerId.isBlank)
      violations += "passengerId is empty"

    if (record.passengerName.isBlank)
      violations += "passengerName is empty"

    if (!record.email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
      violations += s"Invalid email: '${record.email}'"

    if (!record.origin.matches("^[A-Z]{3}$"))
      violations += s"Invalid origin airport code: '${record.origin}'"

    if (!record.destination.matches("^[A-Z]{3}$"))
      violations += s"Invalid destination airport code: '${record.destination}'"

    if (record.origin == record.destination)
      violations += "Origin and destination must be different"

    if (record.seatsRequested < 1 || record.seatsRequested > 9)
      violations += s"seatsRequested must be 1-9, got ${record.seatsRequested}"

    val result = violations.result()
    if (result.isEmpty) Right(record)
    else Left(ValidationError(record, result))
  }
}

package com.skyflow.streaming.infrastructure.stream

import com.skyflow.streaming.domain.model.{BookingRecord, CsvParseError}

import scala.util.Try

/** Utilities cho việc parse CSV */
object CsvParser {

  /** Parse 1 dòng CSV thành BookingRecord. Trả về Either để không throw exception. */
  def parseLine(line: String): Either[CsvParseError, BookingRecord] = {
    val fields = line.split(",", -1).map(_.trim)

    if (fields.length != 6) {
      Left(CsvParseError(line, s"Expected 6 fields, got ${fields.length}"))
    } else {
      val seatsResult = Try(fields(5).toInt).toEither.left.map(_ =>
        CsvParseError(line, s"Invalid seatsRequested: '${fields(5)}'")
      )

      seatsResult.map { seats =>
        BookingRecord(
          passengerId = fields(0),
          passengerName = fields(1),
          email = fields(2),
          origin = fields(3),
          destination = fields(4),
          seatsRequested = seats
        )
      }
    }
  }
}

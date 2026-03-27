package com.skyflow.streaming.application.service

import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.{FileIO, Flow, Keep, Sink, Source}
import akka.stream.IOResult
import akka.stream.scaladsl.Framing
import akka.util.ByteString
import akka.NotUsed
import com.skyflow.streaming.domain.model._
import com.skyflow.streaming.infrastructure.stream.{BookingValidator, CsvParser}

import java.nio.file.Path
import scala.concurrent.Future

/** Feature 1: CSV File Processing Pipeline
  *
  * Concepts học được:
  *   - Source, Flow, Sink — 3 building blocks cơ bản
  *   - FileIO.fromPath / FileIO.toPath — đọc/ghi file dạng stream
  *   - Framing.delimiter — chia ByteString thành dòng
  *   - map, filter, collect — operators biến đổi dữ liệu
  *   - Keep.right / Keep.left — chọn materialized value
  */
object FileProcessingService {

  /** Đọc CSV booking file → parse → validate → ghi output.
    *
    * Pipeline: CSV File → Read bytes → Split lines → Parse → Validate → Write
    * output
    */
  def processBookingsCsv(inputPath: Path, outputPath: Path)(implicit
      system: ActorSystem[_]
  ): Future[IOResult] = {

    // 1. Source: đọc file thành ByteString stream
    val source: Source[ByteString, Future[IOResult]] =
      FileIO.fromPath(inputPath)

    // 2. Flow: chia bytes thành dòng → parse CSV
    val lineFlow: Flow[ByteString, String, NotUsed] =
      Framing
        .delimiter(
          ByteString("\n"),
          maximumFrameLength = 4096,
          allowTruncation = true
        )
        .map(_.utf8String.trim)
        .filterNot(_.isEmpty)

    // 3. Flow: parse CSV line thành BookingRecord (bỏ qua header & dòng lỗi)
    val parseFlow: Flow[String, BookingRecord, NotUsed] =
      Flow[String]
        .drop(1) // bỏ header line
        .map(CsvParser.parseLine)
        .collect { case Right(record) => record } // chỉ giữ records hợp lệ

    // 4. Flow: validate business rules
    val validateFlow: Flow[BookingRecord, ValidatedBooking, NotUsed] =
      Flow[BookingRecord]
        .map(BookingValidator.validate)
        .collect { case Right(record) => record }
        .map(r => ValidatedBooking(r, java.time.Instant.now()))

    // 5. Sink: ghi kết quả ra file
    val sink: Sink[ValidatedBooking, Future[IOResult]] =
      Flow[ValidatedBooking]
        .map(b => ByteString(b.toCsvLine + "\n"))
        .toMat(FileIO.toPath(outputPath))(Keep.right)

    // Kết nối pipeline
    source
      .via(lineFlow)
      .via(parseFlow)
      .via(validateFlow)
      .runWith(sink)
  }

  /** Đếm số records đã xử lý (Materialized value = count thay vì IOResult).
    *
    * Mục đích: Hiểu Keep.right vs Keep.left vs Keep.both
    */
  def processAndCount(inputPath: Path, outputPath: Path)(implicit
      system: ActorSystem[_]
  ): Future[(IOResult, Int)] = {

    implicit val ec = system.executionContext

    val source = FileIO.fromPath(inputPath)

    val pipeline = source
      .via(
        Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 4096,
          allowTruncation = true
        )
      )
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)
      .map(CsvParser.parseLine)
      .collect { case Right(record) => record } // extract valid records
      .map(BookingValidator.validate)
      .collect { case Right(record) => record } // extract valid bookings

    // Sử dụng alsoTo để vừa ghi file vừa đếm
    val fileSink = Flow[BookingRecord]
      .map(r =>
        ByteString(r.toCsvLine + "\n")
      ) // convert to ByteString csv line
      .toMat(FileIO.toPath(outputPath))(Keep.right)

    val countSink = Sink.fold[Int, BookingRecord](0)((count, _) => count + 1)

    // alsoTo ghi file, toMat đếm số lượng
    val (ioResultFut, countFut) = pipeline
      .alsoToMat(fileSink)(Keep.right)
      .toMat(countSink)(Keep.both)
      .run()

    for {
      ioResult <- ioResultFut
      count <- countFut
    } yield (ioResult, count)
  }
}

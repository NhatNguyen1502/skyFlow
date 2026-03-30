package com.skyflow.streaming.application.service

import akka.actor.typed.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.skyflow.streaming.domain.model.BookingRecord
import com.skyflow.streaming.infrastructure.stream.CsvParser

import java.nio.file.Path
import scala.concurrent.Future
import scala.concurrent.duration._

/** Feature 5: Backpressure & Throttling
  *
  * Concepts học được:
  *   - Backpressure — cơ chế tự động giữa producer/consumer (Reactive Streams)
  *   - throttle(elements, per) — rate limiting
  *   - buffer(size, strategy) — đệm khi producer nhanh hơn consumer
  *   - OverflowStrategy — 6 chiến lược khi buffer đầy
  *   - conflate — gom data khi consumer chậm (giữ summary)
  *   - extrapolate — tạo thêm data khi producer chậm
  */
object BackpressureService {

  // ============================================================================
  // 5.1 — Throttle: Rate Limiting
  // ============================================================================

  /** Giới hạn tốc độ xử lý: tối đa `elementsPerSecond` records/giây.
    *
    * Hữu ích khi:
    *   - Gọi external API có rate limit
    *   - Tránh overwhelm DB
    *   - Kiểm soát tài nguyên
    */
  def processWithThrottle(inputPath: Path, elementsPerSecond: Int = 100)(
      implicit system: ActorSystem[_]
  ): Future[ThrottleReport] = {

    implicit val ec = system.executionContext
    val startTime = System.currentTimeMillis()

    readCsvSource(inputPath)
      .throttle(elements = elementsPerSecond, per = 1.second)
      // Thêm maximumBurst cho phép burst ngắn vượt rate
      // .throttle(elements = 100, per = 1.second, maximumBurst = 50)
      .runWith(Sink.fold(0)((count, _) => count + 1))
      .map { count =>
        val durationMs = System.currentTimeMillis() - startTime
        ThrottleReport(
          totalProcessed = count,
          durationMs = durationMs,
          effectiveRate =
            if (durationMs > 0) count * 1000.0 / durationMs else 0.0
        )
      }
  }

  // ============================================================================
  // 5.2 — Buffer + Overflow Strategies
  // ============================================================================

  /** Demo các OverflowStrategy khác nhau:
    *
    *   - backpressure: signal upstream để chậm lại (default, AN TOÀN NHẤT)
    *   - dropHead: bỏ element CŨ nhất trong buffer (latest wins)
    *   - dropTail: bỏ element MỚI nhất trong buffer (oldest wins)
    *   - dropBuffer: xóa TOÀN BỘ buffer
    *   - dropNew: bỏ element incoming (buffer giữ nguyên)
    *   - fail: fail stream khi buffer đầy
    */
  def processWithBuffer(
      inputPath: Path,
      bufferSize: Int,
      strategy: OverflowStrategy
  )(implicit system: ActorSystem[_]): Future[Int] = {

    readCsvSource(inputPath)
      .buffer(size = bufferSize, overflowStrategy = strategy)
      .mapAsync(1)(simulateSlowProcessing)
      .runWith(Sink.fold(0)((count, _) => count + 1))
  }

  /** So sánh 3 strategies phổ biến trên cùng 1 input. Khi chạy xong, so sánh
    * totalProcessed: backpressure sẽ xử lý hết, dropHead/dropNew có thể mất
    * data.
    */
  def compareBufferStrategies(
      inputPath: Path
  )(implicit system: ActorSystem[_]): Future[Map[String, Int]] = {

    implicit val ec = system.executionContext

    for {
      backpressure <- processWithBuffer(
        inputPath,
        100,
        OverflowStrategy.backpressure
      )
      dropHead <- processWithBuffer(inputPath, 100, OverflowStrategy.dropHead)
      dropNew <- processWithBuffer(inputPath, 100, OverflowStrategy.dropNew)
    } yield Map(
      "backpressure" -> backpressure,
      "dropHead" -> dropHead,
      "dropNew" -> dropNew
    )
  }

  // ============================================================================
  // 5.3 — Conflate: Gom data khi consumer chậm
  // ============================================================================

  /** conflate: Khi consumer chậm hơn producer, GOM nhiều elements thành 1
    * summary. Không mất data — data được merge/aggregate thay vì drop.
    *
    * Ví dụ: 1000 booking records → consumer chỉ kịp xử lý 100 summaries.
    */
  def processWithConflate(
      inputPath: Path
  )(implicit system: ActorSystem[_]): Future[Seq[RouteSummary]] = {

    readCsvSource(inputPath)
      .conflateWithSeed(record =>
        RouteSummary(record.routeKey, 1, record.seatsRequested)
      ) { (summary, record) =>
        // Gom nhiều records thành 1 summary
        if (record.routeKey == summary.routeKey) {
          summary.copy(
            recordCount = summary.recordCount + 1,
            totalSeats = summary.totalSeats + record.seatsRequested
          )
        } else {
          // Route khác → giữ summary cũ (simplified demo)
          summary.copy(recordCount = summary.recordCount + 1)
        }
      }
      .mapAsync(1)(simulateSlowSummaryProcessing)
      .runWith(Sink.seq)
  }

  // ============================================================================
  // 5.4 — Benchmark: Đo throughput
  // ============================================================================

  /** Đo throughput thực tế: xử lý file và in progress mỗi N records.
    */
  def benchmarkThroughput(inputPath: Path, reportEvery: Int = 100)(implicit
      system: ActorSystem[_]
  ): Future[ThrottleReport] = {

    implicit val ec = system.executionContext
    val startTime = System.currentTimeMillis()

    readCsvSource(inputPath).zipWithIndex
      .wireTap { case (_, idx) =>
        if ((idx + 1) % reportEvery == 0) {
          val elapsed = System.currentTimeMillis() - startTime
          val rate = (idx + 1) * 1000.0 / elapsed
          println(
            f"[Progress] Processed ${idx + 1} records, rate: $rate%.1f records/sec"
          )
        }
      }
      .map(_._1) // bỏ index, giữ record
      .runWith(Sink.fold(0)((count, _) => count + 1))
      .map { count =>
        val durationMs = System.currentTimeMillis() - startTime
        ThrottleReport(
          totalProcessed = count,
          durationMs = durationMs,
          effectiveRate =
            if (durationMs > 0) count * 1000.0 / durationMs else 0.0
        )
      }
  }

  // --- Helpers ---

  private def readCsvSource(inputPath: Path): Source[BookingRecord, NotUsed] =
    FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)
      .map(CsvParser.parseLine)
      .collect { case Right(record) => record }
      .mapMaterializedValue(_ => NotUsed)

  /** Mô phỏng xử lý chậm (DB write, API call) */
  private def simulateSlowProcessing(
      record: BookingRecord
  )(implicit system: ActorSystem[_]): Future[BookingRecord] = {
    akka.pattern.after(10.millis)(Future.successful(record))(
      system.classicSystem
    )
  }

  private def simulateSlowSummaryProcessing(
      summary: RouteSummary
  )(implicit system: ActorSystem[_]): Future[RouteSummary] = {
    akka.pattern.after(50.millis)(Future.successful(summary))(
      system.classicSystem
    )
  }
}

/** Summary khi conflate gom nhiều records */
final case class RouteSummary(
    routeKey: String,
    recordCount: Int,
    totalSeats: Int
)

/** Báo cáo throughput */
final case class ThrottleReport(
    totalProcessed: Int,
    durationMs: Long,
    effectiveRate: Double
)

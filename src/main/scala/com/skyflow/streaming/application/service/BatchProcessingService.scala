package com.skyflow.streaming.application.service

import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.{FileIO, Flow, Framing, Keep, Sink, Source}
import akka.util.ByteString
import akka.NotUsed
import com.skyflow.streaming.domain.model._
import com.skyflow.streaming.infrastructure.stream.{BookingValidator, CsvParser}

import java.nio.file.Path
import scala.concurrent.Future

/** Feature 3: Batch Processing Pipeline
  *
  * Concepts học được:
  *   - groupBy(maxSubstreams, key) — chia stream thành SubFlows
  *   - mergeSubstreams — gom SubFlows lại thành 1 stream
  *   - fold — aggregation cuối stream (emit 1 lần)
  *   - scan — running aggregation (emit mỗi bước)
  *   - grouped(n) — tạo micro-batches trong mỗi SubFlow
  */
object BatchProcessingService {

  /** Đọc bookings CSV → nhóm theo route → xử lý batch → tổng hợp kết quả.
    *
    * Pipeline: CSV → Parse → GroupBy(route) → Grouped(batchSize) → Process
    * batch → MergeSubstreams → Report
    */
  def processByRoute(inputPath: Path, batchSize: Int = 50)(implicit
      system: ActorSystem[_]
  ): Future[BatchReport] = {

    implicit val ec = system.executionContext

    readAndParseFile(inputPath)
      .groupBy(maxSubstreams = 100, _.routeKey) // chia theo route
      .grouped(batchSize) // micro-batch trong mỗi route
      .map(processBatch) // xử lý từng batch
      .mergeSubstreams // gom lại
      .runWith(Sink.fold(BatchReport.empty)(_.merge(_))) // tổng hợp
  }

  /** Variant: dùng scan thay vì fold. scan emit kết quả tích lũy SAU MỖI
    * element (chứ không đợi cuối stream).
    *
    * Hữu ích khi muốn theo dõi progress realtime.
    */
  def processByRouteWithProgress(inputPath: Path, batchSize: Int = 50)(implicit
      system: ActorSystem[_]
  ): Future[Seq[BatchReport]] = {

    readAndParseFile(inputPath)
      .groupBy(maxSubstreams = 100, _.routeKey)
      .grouped(batchSize)
      .map(processBatch)
      .mergeSubstreams
      // scan: emit running total sau mỗi batch
      .scan(BatchReport.empty)(_.merge(_))
      .runWith(Sink.seq) // thu thập tất cả snapshots
  }

  /** Demo fold vs reduce vs scan:
    *   - fold(zero)(f) — cần giá trị khởi tạo, emit 1 lần cuối stream
    *   - reduce(f) — không cần zero, fail nếu stream rỗng, emit 1 lần cuối
    *     stream
    *   - scan(zero)(f) — như fold, nhưng emit SAU MỖI element
    */
  def aggregationDemo(
      inputPath: Path
  )(implicit system: ActorSystem[_]): Future[Unit] = {

    implicit val ec = system.executionContext
    val source = readAndParseFile(inputPath)

    // fold: Tổng số ghế requested → 1 con số cuối cùng
    val totalSeatsFuture: Future[Int] = source
      .map(_.seatsRequested)
      .runWith(Sink.fold(0)(_ + _))

    // scan: Tổng tích lũy → 1 stream các running totals
    val runningTotalFuture: Future[Seq[Int]] = source
      .map(_.seatsRequested)
      .scan(0)(_ + _)
      .runWith(Sink.seq)

    for {
      total <- totalSeatsFuture
      runningTotals <- runningTotalFuture
    } yield {
      println(s"Total seats (fold): $total")
      println(s"Running totals (scan): ${runningTotals.mkString(", ")}")
    }
  }

  // --- Helper methods ---

  private def readAndParseFile(
      inputPath: Path
  ): Source[BookingRecord, NotUsed] =
    FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1) // header
      .map(CsvParser.parseLine)
      .collect { case Right(record) => record }
      .mapMaterializedValue(_ => NotUsed) // replace IOResult by NotUsed

  private def processBatch(batch: Seq[BookingRecord]): BatchReport = {
    val routeKey = batch.headOption.map(_.routeKey).getOrElse("unknown")
    val totalSeats = batch.map(_.seatsRequested).sum

    BatchReport(
      routeReports = Map(
        routeKey -> RouteReport(
          routeKey = routeKey,
          recordCount = batch.size,
          totalSeatsRequested = totalSeats
        )
      ),
      totalProcessed = batch.size,
      totalSeatsRequested = totalSeats
    )
  }
}

/** Báo cáo 1 route */
final case class RouteReport(
    routeKey: String,
    recordCount: Int,
    totalSeatsRequested: Int
) {
  def merge(other: RouteReport): RouteReport = copy(
    recordCount = recordCount + other.recordCount,
    totalSeatsRequested = totalSeatsRequested + other.totalSeatsRequested
  )
}

/** Báo cáo tổng batch processing */
final case class BatchReport(
    routeReports: Map[String, RouteReport],
    totalProcessed: Int,
    totalSeatsRequested: Int
) {
  def merge(other: BatchReport): BatchReport = BatchReport(
    routeReports = (routeReports.toSeq ++ other.routeReports.toSeq)
      .groupBy(_._1)
      .map { case (key, reports) =>
        key -> reports.map(_._2).reduce(_.merge(_))
      },
    totalProcessed = totalProcessed + other.totalProcessed,
    totalSeatsRequested = totalSeatsRequested + other.totalSeatsRequested
  )
}

object BatchReport {
  def empty: BatchReport = BatchReport(Map.empty, 0, 0)
}

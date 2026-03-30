package com.skyflow.streaming.application.service

import akka.actor.typed.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.NotUsed
import com.skyflow.streaming.domain.model._
import com.skyflow.streaming.infrastructure.stream.{BookingValidator, CsvParser}

import java.nio.file.Path
import scala.concurrent.Future
import scala.concurrent.duration._

/** Feature 4: Error Handling & Supervision trong Akka Streams
  *
  * Concepts học được:
  *   - Supervision Strategy: Resume / Restart / Stop
  *   - Either pattern — wrap lỗi thay vì throw exception
  *   - Partition — chia stream thành success/error branches
  *   - recover / recoverWith — fallback khi stage fail
  *   - RestartSource.withBackoff — auto-retry với exponential backoff
  */
object ErrorHandlingService {

  // ============================================================================
  // 4.1 — Supervision Strategy (stream-level error handling)
  // ============================================================================

  /** Supervision strategy quyết định hành vi khi 1 stage throw exception:
    *   - Resume: bỏ qua element gây lỗi, stream tiếp tục
    *   - Restart: reset state của stage, stream tiếp tục
    *   - Stop: dừng toàn bộ stream (default)
    */
  def processWithSupervision(inputPath: Path, outputPath: Path)(implicit
      system: ActorSystem[_]
  ): Future[IOResult] = {

    val decider: Supervision.Decider = {
      case _: NumberFormatException =>
        Supervision.Resume // bỏ qua dòng CSV lỗi số
      case _: IllegalArgumentException =>
        Supervision.Resume // bỏ qua dữ liệu invalid
      case _ => Supervision.Stop // lỗi khác: dừng stream
    }

    FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)
      .map(parseLineUnsafe) // CÓ THỂ throw exception! Supervision sẽ xử lý
      .map(validateUnsafe)
      .map(r => ByteString(r.toCsvLine + "\n"))
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .runWith(FileIO.toPath(outputPath))
  }

  // ============================================================================
  // 4.2 — Either Pattern (element-level error handling) — KHUYẾN KHÍCH
  // ============================================================================

  /** Thay vì throw exception, wrap mọi kết quả trong Either[Error, Success]. An
    * toàn hơn supervision vì:
    *   - Explicit: compiler bắt buộc xử lý cả 2 case
    *   - Không mất data: error records vẫn được thu thập
    *   - Testable: dễ test hơn so với side-effect exceptions
    */
  def processWithEitherPattern(
      inputPath: Path,
      outputPath: Path,
      errorPath: Path
  )(implicit system: ActorSystem[_]): Future[ErrorHandlingReport] = {

    implicit val ec = system.executionContext

    val source = FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)

    // Parse an toàn — KHÔNG throw exception
    val safeParse: Flow[String, Either[StreamError, BookingRecord], NotUsed] =
      Flow[String].map(CsvParser.parseLine)

    // Validate an toàn
    val safeValidate: Flow[
      Either[StreamError, BookingRecord],
      Either[StreamError, BookingRecord],
      NotUsed
    ] =
      Flow[Either[StreamError, BookingRecord]].map {
        case Right(record) => BookingValidator.validate(record)
        case left          => left
      }

    // Chia thành 2 nhánh: success và error
    val errorSink: Sink[StreamError, Future[IOResult]] =
      Flow[StreamError]
        .map(e => ByteString(s"${e.line.getOrElse("")}|${e.message}\n"))
        .toMat(FileIO.toPath(errorPath))(Keep.right)

    val successSink: Sink[BookingRecord, Future[IOResult]] =
      Flow[BookingRecord]
        .map(r => ByteString(r.toCsvLine + "\n"))
        .toMat(FileIO.toPath(outputPath))(Keep.right)

    // Sử dụng divertTo thay vì GraphDSL — đơn giản hơn cho case 2 nhánh
    val errorCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val successCount = new java.util.concurrent.atomic.AtomicInteger(0)

    source
      .via(safeParse)
      .via(safeValidate)
      .divertTo(
        // Nhánh error: extract error và ghi vào file
        Flow[Either[StreamError, BookingRecord]]
          .collect { case Left(err) => err }
          .wireTap(e => errorCount.incrementAndGet())
          .to(errorSink),
        // Điều kiện rẽ nhánh
        _.isLeft
      )
      // Nhánh chính: success records
      .collect { case Right(record) => record }
      .wireTap(_ => successCount.incrementAndGet())
      .runWith(successSink)
      .map { _ =>
        ErrorHandlingReport(
          successCount = successCount.get(),
          errorCount = errorCount.get()
        )
      }
  }

  // ============================================================================
  // 4.3 — Graph DSL Partition: Full DLQ (Dead Letter Queue) pattern
  // ============================================================================

  /** Dùng GraphDSL + Partition để chia stream thành N nhánh rõ ràng. Advanced
    * hơn divertTo — cho phép > 2 nhánh.
    */
  def processWithDLQ(inputPath: Path, outputPath: Path, dlqPath: Path)(implicit
      system: ActorSystem[_]
  ): Future[ErrorHandlingReport] = {

    implicit val ec = system.executionContext

    val errorCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val successCount = new java.util.concurrent.atomic.AtomicInteger(0)

    // Materialize both file sinks so we can await graph completion
    val (dlqResult, outputResult) = RunnableGraph
      .fromGraph(
        GraphDSL.create(FileIO.toPath(dlqPath), FileIO.toPath(outputPath))(
          (_, _)
        ) { implicit builder => (dlqSink, outputSink) =>
          import GraphDSL.Implicits._

          // Source
          val source = builder.add(
            FileIO
              .fromPath(inputPath)
              .via(
                Framing
                  .delimiter(ByteString("\n"), 4096, allowTruncation = true)
              )
              .map(_.utf8String.trim)
              .filterNot(_.isEmpty)
              .drop(1)
              .map(CsvParser.parseLine)
          )

          // Partition: 0 = error, 1 = success
          val partition = builder.add(
            Partition[Either[StreamError, BookingRecord]](
              2,
              {
                case Left(_)  => 0
                case Right(_) => 1
              }
            )
          )

          // Parse error branch — count + format for DLQ
          val parseErrorFlow = builder.add(
            Flow[Either[StreamError, BookingRecord]]
              .collect { case Left(err) => err }
              .wireTap(_ => errorCount.incrementAndGet())
              .map(e => ByteString(s"${e.line.getOrElse("")}|${e.message}\n"))
          )

          // Validation flow
          val validationFlow = builder.add(
            Flow[Either[StreamError, BookingRecord]].map {
              case Right(record) => BookingValidator.validate(record)
              case left          => left
            }
          )

          val validationPartition = builder.add(
            Partition[Either[StreamError, BookingRecord]](
              2,
              {
                case Left(_)  => 0
                case Right(_) => 1
              }
            )
          )

          // Validation error branch — count + format for DLQ
          val validationErrorFlow = builder.add(
            Flow[Either[StreamError, BookingRecord]]
              .collect { case Left(err) => err }
              .wireTap(_ => errorCount.incrementAndGet())
              .map(e => ByteString(s"${e.line.getOrElse("")}|${e.message}\n"))
          )

          // Merge both error branches into the single DLQ sink
          val errorMerge = builder.add(Merge[ByteString](2))

          // Success branch — count + format for output
          val successFlow = builder.add(
            Flow[Either[StreamError, BookingRecord]]
              .collect { case Right(record) => record }
              .wireTap(_ => successCount.incrementAndGet())
              .map(r => ByteString(r.toCsvLine + "\n"))
          )

          // Wiring
          source ~> partition
          partition.out(0) ~> parseErrorFlow ~> errorMerge ~> dlqSink
          partition.out(1) ~> validationFlow ~> validationPartition
          validationPartition.out(0) ~> validationErrorFlow ~> errorMerge
          validationPartition.out(1) ~> successFlow ~> outputSink

          ClosedShape
        }
      )
      .run()

    // Await both sinks to complete, then return the collected counts
    for {
      _ <- dlqResult
      _ <- outputResult
    } yield ErrorHandlingReport(
      successCount = successCount.get(),
      errorCount = errorCount.get()
    )
  }

  // ============================================================================
  // 4.4 — RestartSource: Auto-retry với exponential backoff
  // ============================================================================

  /** Tự động restart stream khi gặp lỗi transient. Hữu ích cho: DB connection
    * lost, network timeout, external API errors.
    */
  def restartableProcessing(
      inputPath: Path
  )(implicit system: ActorSystem[_]): Future[Seq[BookingRecord]] = {

    val restartSettings = RestartSettings(
      minBackoff = 1.second, // đợi 1s trước khi retry lần 1
      maxBackoff = 30.seconds, // tối đa đợi 30s
      randomFactor = 0.2 // jitter ±20% để tránh thundering herd
    ).withMaxRestarts(5, within = 1.minute) // tối đa 5 lần restart trong 1 phút

    // RestartSource tự động restart toàn bộ source khi fail
    RestartSource
      .withBackoff(restartSettings) { () =>
        FileIO
          .fromPath(inputPath)
          .via(
            Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true)
          )
          .map(_.utf8String.trim)
          .filterNot(_.isEmpty)
          .drop(1)
          .map(CsvParser.parseLine)
          .collect { case Right(record) => record }
      }
      .runWith(Sink.seq)
  }

  // ============================================================================
  // 4.5 — recover / recoverWith: Fallback khi stream fail
  // ============================================================================

  /** recover: khi stream gặp exception, emit 1 element cuối cùng rồi complete.
    * recoverWith: thay thế stream bị lỗi bằng 1 stream khác.
    */
  def processWithRecover(
      inputPath: Path
  )(implicit system: ActorSystem[_]): Future[Seq[BookingRecord]] = {

    FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)
      .map(parseLineUnsafe) // CÓ THỂ throw!
      // recover: nếu fail, emit 1 record "sentinel" rồi kết thúc
      .recover { case ex: Exception =>
        BookingRecord("ERROR", "Stream Failed", ex.getMessage, "N/A", "N/A", 0)
      }
      .runWith(Sink.seq)
  }

  // --- Unsafe helpers (dùng cho demo supervision) ---

  /** Parse line — throw exception nếu lỗi (KHÔNG an toàn!) */
  private def parseLineUnsafe(line: String): BookingRecord =
    CsvParser.parseLine(line) match {
      case Right(record) => record
      case Left(error)   => throw new IllegalArgumentException(error.reason)
    }

  /** Validate — throw exception nếu lỗi (KHÔNG an toàn!) */
  private def validateUnsafe(record: BookingRecord): BookingRecord =
    BookingValidator.validate(record) match {
      case Right(valid) => valid
      case Left(error)  =>
        throw new IllegalArgumentException(error.violations.mkString(", "))
    }
}

/** Báo cáo error handling */
final case class ErrorHandlingReport(
    successCount: Int,
    errorCount: Int
) {
  def totalCount: Int = successCount + errorCount
}

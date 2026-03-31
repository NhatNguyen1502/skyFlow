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

/** Feature 6: Graph DSL — Fan-out / Fan-in
  *
  * Concepts học được:
  *   - GraphDSL.create() — builder pattern cho complex graphs
  *   - Broadcast[T] — 1 input → N outputs (clone mọi element tới tất cả
  *     outputs)
  *   - Balance[T] — 1 input → N outputs (round-robin, load balancing)
  *   - Merge[T] — N inputs → 1 output
  *   - Partition[T] — 1 input → N outputs (theo điều kiện)
  *   - Zip[A, B] — 2 inputs → 1 output (tuple)
  *   - ~> operator — connect các stages
  *   - ClosedShape — graph hoàn chỉnh, sẵn sàng chạy
  */
object GraphDslService {

  // ============================================================================
  // 6.1 — Broadcast: Gửi data đến nhiều destinations cùng lúc
  // ============================================================================

  /** Broadcast: mỗi element được CLONE tới TẤT CẢ outputs.
    *
    * Ví dụ: Đọc CSV → gửi đồng thời tới:
    *   1. File output (lưu trữ)
    *   2. Console log (monitoring)
    *   3. Counter (statistics)
    */
  def broadcastPipeline(inputPath: Path, outputPath: Path)(implicit
      system: ActorSystem[_]
  ): Future[Int] = {

    val countSink = Sink.fold[Int, BookingRecord](0)((c, _) => c + 1)

    RunnableGraph
      .fromGraph(GraphDSL.create(countSink) { implicit builder => countMat =>
        import GraphDSL.Implicits._

        // Source
        val source = builder.add(csvSource(inputPath))

        // Broadcast: 1 input → 3 outputs
        val bcast = builder.add(Broadcast[BookingRecord](3))

        // Sink 1: ghi file
        val fileSink = builder.add(
          Flow[BookingRecord]
            .map(r => ByteString(r.toCsvLine + "\n"))
            .to(FileIO.toPath(outputPath))
        )

        // Sink 2: log mỗi record
        val logSink = builder.add(
          Sink.foreach[BookingRecord](r =>
            println(s"[Broadcast] Processing: ${r.passengerId} → ${r.routeKey}")
          )
        )

        // Sink 3: đếm (materialized value — already added via GraphDSL.create)

        // Wiring: source → broadcast → 3 sinks
        source ~> bcast
        bcast ~> fileSink
        bcast ~> logSink
        bcast ~> countMat

        ClosedShape
      })
      .run()
  }

  // ============================================================================
  // 6.2 — Balance + Merge: Parallel Workers
  // ============================================================================

  /** Balance: phân phối elements ROUND-ROBIN tới N workers. Merge: gom kết quả
    * từ N workers lại thành 1 stream.
    *
    * Khác với Broadcast: Balance gửi mỗi element tới CHỈ 1 worker. → Dùng để
    * parallel processing khi mỗi element chỉ cần xử lý 1 lần.
    */
  def balancedWorkers(inputPath: Path, workerCount: Int = 4)(implicit
      system: ActorSystem[_]
  ): Future[Seq[String]] = {

    RunnableGraph
      .fromGraph(GraphDSL.create(Sink.seq[String]) {
        implicit builder => seqMat =>
          import GraphDSL.Implicits._

          val source = builder.add(csvSource(inputPath))
          val balance = builder.add(Balance[BookingRecord](workerCount))
          val merge = builder.add(Merge[String](workerCount))

          source ~> balance

          // Tạo N workers, mỗi worker xử lý một phần data
          for (i <- 0 until workerCount) {
            val worker = builder.add(
              Flow[BookingRecord].map { record =>
                s"[Worker-$i] Processed ${record.passengerId} on ${record.routeKey}"
              }.async
            )
            balance ~> worker ~> merge
          }

          merge ~> seqMat

          ClosedShape
      })
      .run()
  }

  // ============================================================================
  // 6.3 — Partition + Merge: Route-based Processing
  // ============================================================================

  /** Partition: chia stream theo ĐIỀU KIỆN (không phải round-robin).
    *
    * Ví dụ: Chia bookings theo priority:
    *   - Outlet 0: domestic (cùng quốc gia)
    *   - Outlet 1: unknown/other routes
    */
  def partitionByType(
      inputPath: Path,
      domesticOutputPath: Path,
      otherOutputPath: Path
  )(implicit system: ActorSystem[_]): Future[Done] = {

    val domesticRoutes =
      Set("HAN_SGN", "HAN_DAD", "SGN_DAD", "SGN_PQC", "HAN_CXR", "DAD_PQC")

    RunnableGraph
      .fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val source = builder.add(csvSource(inputPath))
        val partition = builder.add(
          Partition[BookingRecord](
            2,
            record => if (domesticRoutes.contains(record.routeKey)) 0 else 1
          )
        )

        val domesticSink = builder.add(
          Flow[BookingRecord]
            .map(r => ByteString(r.toCsvLine + "\n"))
            .to(FileIO.toPath(domesticOutputPath))
        )

        val otherSink = builder.add(
          Flow[BookingRecord]
            .map(r => ByteString(r.toCsvLine + "\n"))
            .to(FileIO.toPath(otherOutputPath))
        )

        source ~> partition
        partition.out(0) ~> domesticSink
        partition.out(1) ~> otherSink

        ClosedShape
      })
      .run()

    Future.successful(Done)
  }

  // ============================================================================
  // 6.4 — Zip: Kết hợp 2 sources
  // ============================================================================

  /** Zip: lấy 1 element từ MỖI input, kết hợp thành tuple.
    *
    * Ví dụ: Zip booking records với sequential IDs. Lưu ý: Zip chờ cả 2 inputs
    * → stream chậm theo source chậm nhất.
    */
  def zipWithSequence(
      inputPath: Path
  )(implicit system: ActorSystem[_]): Future[Seq[(Long, BookingRecord)]] = {

    RunnableGraph
      .fromGraph(
        GraphDSL.create(Sink.seq[(Long, BookingRecord)]) {
          implicit builder => seqMat =>
            import GraphDSL.Implicits._

            val csvSrc = builder.add(csvSource(inputPath))
            val idSrc = builder.add(
              Source.fromIterator(() => Iterator.from(1).map(_.toLong))
            )
            val zip = builder.add(Zip[Long, BookingRecord])

            idSrc ~> zip.in0
            csvSrc ~> zip.in1
            zip.out ~> seqMat

            ClosedShape
        }
      )
      .run()
  }

  // ============================================================================
  // 6.5 — Reusable Flow Shape (Custom Graph)
  // ============================================================================

  /** Tạo Flow có 1 input và 2 outputs (success + error). Đây là ví dụ custom
    * Shape — ngoài ClosedShape tiêu chuẩn.
    *
    * Trả về Flow[String, BookingRecord, NotUsed] kèm error output.
    */
  def parseWithErrorBranch
      : Graph[FanOutShape2[String, BookingRecord, String], NotUsed] = {
    GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val parse = builder.add(Flow[String].map(CsvParser.parseLine))
      val partition = builder.add(
        Partition[Either[Any, BookingRecord]](
          2,
          {
            case Left(_)  => 0
            case Right(_) => 1
          }
        )
      )

      val extractError = builder.add(
        Flow[Either[Any, BookingRecord]].collect { case Left(err) =>
          err.toString
        }
      )
      val extractSuccess = builder.add(
        Flow[Either[Any, BookingRecord]].collect { case Right(record) =>
          record
        }
      )

      parse ~> partition
      partition.out(0) ~> extractError
      partition.out(1) ~> extractSuccess

      new FanOutShape2(parse.in, extractSuccess.out, extractError.out)
    }
  }

  // --- Helper ---

  private def csvSource(inputPath: Path): Source[BookingRecord, NotUsed] =
    FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)
      .map(CsvParser.parseLine)
      .collect { case Right(record) => record }
      .mapMaterializedValue(_ => NotUsed)
}

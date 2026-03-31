package com.skyflow.streaming

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.stream.ActorAttributes
import akka.stream.Supervision
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.NotUsed
import com.skyflow.streaming.domain.model._
import com.skyflow.streaming.infrastructure.stream.{BookingValidator, CsvParser}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

/** Hướng dẫn test Akka Streams — 6 pattern chính.
  *
  * ┌──────────────────────────────────────────────────────────────────┐ │
  * PATTERN 1 │ Sink.seq + futureValue — thu kết quả vào List │ │ PATTERN 2 │
  * Test Flow bằng Source(...) + Sink.seq │ │ PATTERN 3 │ TestSource.probe /
  * TestSink.probe — kiểm soát tay │ │ PATTERN 4 │ Either stream — đếm
  * success/failure │ │ PATTERN 5 │ groupBy / fold — batch & aggregation │ │
  * PATTERN 6 │ Supervision strategy — Resume / Stop │
  * └──────────────────────────────────────────────────────────────────┘
  *
  * Deps cần trong build.sbt (đã có): "akka-stream-testkit" % Test
  * "akka-actor-testkit-typed" % Test "scalatest" % Test
  */
class StreamPipelineSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures {

  // ── ActorSystem cho tất cả tests ─────────────────────────────────────────
  // ActorTestKit quản lý vòng đời ActorSystem, đảm bảo cleanup sau mỗi test suite.
  private val testKit = ActorTestKit()

  // Dùng classic ActorSystem làm implicit DUY NHẤT vì:
  //   1. TestSource.probe / TestSink.probe yêu cầu akka.actor.ActorSystem (classic)
  //   2. Akka Streams tự derive implicit Materializer từ classic system
  //   3. Tránh ambiguity khi có cả typed + classic trong implicit scope
  implicit val system: akka.actor.ActorSystem = testKit.system.toClassic

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    super.afterAll()
  }

  // ── fixture helpers ───────────────────────────────────────────────────────

  private def record(
      id: String = "P001",
      origin: String = "HAN",
      dest: String = "SGN"
  ) =
    BookingRecord(
      passengerId = id,
      passengerName = "Nguyen Van A",
      email = "vana@example.com",
      origin = origin,
      destination = dest,
      seatsRequested = 2
    )

  private def csvLine(
      id: String = "P001",
      origin: String = "HAN",
      dest: String = "SGN",
      seats: String = "2"
  ) =
    s"$id,Nguyen Van A,vana@example.com,$origin,$dest,$seats"

  // ==========================================================================
  // PATTERN 1 — Sink.seq + ScalaFutures
  // ==========================================================================
  //
  //  Cách dùng phổ biến nhất: chạy stream, chờ kết quả bằng .futureValue,
  //  rồi assert như list thông thường.
  //
  //  Source(list)           ← nguồn tĩnh
  //    .map / .filter       ← biến đổi
  //    .runWith(Sink.seq)   ← vật chất hoá thành Future[Seq[T]]
  //  result.futureValue     ← block chờ (do ScalaFutures)
  // ==========================================================================

  "PATTERN 1 — Sink.seq + futureValue" should {

    "map và filter trên Source cơ bản" in {
      val result = Source(1 to 10)
        .filter(_ % 2 == 0)
        .map(_ * 10)
        .runWith(Sink.seq)

      // futureValue tự động block & unwrap Future
      result.futureValue shouldBe Seq(20, 40, 60, 80, 100)
    }

    "trả về Seq rỗng cho Source.empty" in {
      val result = Source
        .empty[Int]
        .map(_ * 2)
        .runWith(Sink.seq)

      result.futureValue shouldBe empty
    }

    "fold tích lũy về 1 giá trị" in {
      val total = Source(Seq(1, 2, 3, 4, 5))
        .runWith(Sink.fold(0)(_ + _))

      total.futureValue shouldBe 15
    }

    "collect bỏ None và giữ Some" in {
      val result = Source(Seq(Some(1), None, Some(3), None, Some(5)))
        .collect { case Some(v) => v }
        .runWith(Sink.seq)

      result.futureValue shouldBe Seq(1, 3, 5)
    }
  }

  // ==========================================================================
  // PATTERN 2 — Test các Flow từ codebase
  // ==========================================================================
  //
  //  Bọc logic thực tế vào Flow, test bằng Source + Sink.seq.
  //  Không cần file I/O; dùng Source(Seq(...)) thay thế.
  // ==========================================================================

  "PATTERN 2 — Test Flow thực tế (CsvParser + BookingValidator)" should {

    "CsvParser: parse dòng hợp lệ → Right(BookingRecord)" in {
      val lines = Seq(
        csvLine("P001"),
        csvLine("P002", "SGN", "HAN")
      )

      // Định nghĩa Flow một cách tường minh để dễ tái sử dụng
      val parseFlow: Flow[String, Either[StreamError, BookingRecord], NotUsed] =
        Flow[String].map(CsvParser.parseLine)

      val results = Source(lines)
        .via(parseFlow)
        .runWith(Sink.seq)
        .futureValue

      results should have size 2
      results.forall(_.isRight) shouldBe true
      results.collect { case Right(r) => r.passengerId } shouldBe Seq(
        "P001",
        "P002"
      )
    }

    "CsvParser: dòng sai format → Left(CsvParseError)" in {
      val badLines = Seq("ONLY,THREE,FIELDS", "", "two,fields")

      val results = Source(badLines)
        .map(CsvParser.parseLine)
        .runWith(Sink.seq)
        .futureValue

      results.forall(_.isLeft) shouldBe true
    }

    "BookingValidator: record hợp lệ → Right; invalid → Left" in {
      val records = Seq(
        record("P001"), // hợp lệ
        record("P002").copy(email = "not-email"), // email sai
        record("P003").copy(seatsRequested = 0) // seats ngoài range
      )

      val results = Source(records)
        .map(BookingValidator.validate)
        .runWith(Sink.seq)
        .futureValue

      results(0) shouldBe Right(records(0))
      results(1) shouldBe a[Left[_, _]]
      results(2) shouldBe a[Left[_, _]]
    }

    "Pipeline đầy đủ: parse → validate → chỉ giữ hợp lệ" in {
      //  parse error ──┐
      //  valid ─────────┤→ Right → validate → Right → collect
      //  validation err─┘         → Left  → bỏ

      val lines = Seq(
        csvLine("P001"), // hợp lệ ✓
        "BAD,LINE", // parse error ✗
        csvLine("P003", seats = "99"), // validation error (seats > 9) ✗
        csvLine("P004", "DAD", "SGN") // hợp lệ ✓
      )

      val validPassengerIds = Source(lines)
        .map(CsvParser.parseLine)
        .collect { case Right(r) => r }
        .map(BookingValidator.validate)
        .collect { case Right(r) => r }
        .map(_.passengerId)
        .runWith(Sink.seq)
        .futureValue

      validPassengerIds shouldBe Seq("P001", "P004")
    }
  }

  // ==========================================================================
  // PATTERN 3 — TestSource.probe + TestSink.probe
  // ==========================================================================
  //
  //  Khi cần kiểm soát từng step:
  //   • TestSource.probe[T]  → "faucet" bạn vặn tay (sendNext, sendComplete)
  //   • TestSink.probe[T]    → "bucket" bạn kéo tay (request, expectNext)
  //
  //  Quan trọng: TestSink PHẢI gọi request(n) trước khi nhận element
  //              vì Reactive Streams là pull-based (backpressure).
  // ==========================================================================

  "PATTERN 3 — TestSource.probe + TestSink.probe" should {

    "đẩy và nhận element theo cách thủ công" in {
      // Keep.both giữ materialized value của cả source (Probe) và sink (Probe)
      val (pub, sub) = TestSource
        .probe[BookingRecord]
        .map(r => r.copy(passengerName = r.passengerName.toUpperCase))
        .toMat(TestSink.probe[BookingRecord])(Keep.both)
        .run()

      sub.request(1) // bước 1: kéo demand
      pub.sendNext(record("P1")) // bước 2: đẩy element

      sub.expectNext().passengerName shouldBe "NGUYEN VAN A"

      pub.sendComplete()
      sub.expectComplete()
    }

    "backpressure: chỉ nhận đúng số element đã request" in {
      val (pub, sub) = TestSource
        .probe[Int]
        .toMat(TestSink.probe[Int])(Keep.both)
        .run()

      // Bước 1: request 2 → tạo demand cho publisher
      sub.request(2)
      // Bước 2: send đúng 2 element (có demand sẵn)
      pub.sendNext(10)
      pub.sendNext(20)
      sub.expectNext(10, 20)

      // Không có demand → TestPublisher sẽ block nếu sendNext ngay
      // → đây chính là backpressure: subscriber kiểm soát tốc độ
      sub.expectNoMessage() // không có gì pending

      // Bước 3: request thêm 1 → send 1 nữa
      sub.request(1)
      pub.sendNext(30) // bây giờ có demand, send thành công
      sub.expectNext(30)

      pub.sendComplete()
      sub.expectComplete()
    }

    "upstream error được lan truyền tới subscriber" in {
      val boom = new RuntimeException("upstream boom")

      val (pub, sub) = TestSource
        .probe[String]
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

      sub.request(1)
      pub.sendError(boom)
      sub.expectError(boom)
    }

    "stream rỗng: sendComplete sau khi đã subscribe → expectComplete" in {
      val (pub, sub) = TestSource
        .probe[Int]
        .toMat(TestSink.probe[Int])(Keep.both)
        .run()

      // request(1) khởi động subscription trước; rồi publisher complete
      sub.request(1)
      pub.sendComplete()
      sub.expectComplete()
    }
  }

  // ==========================================================================
  // PATTERN 4 — Either pattern: đếm success/failure trong stream
  // ==========================================================================
  //
  //  Thay vì throw exception (Supervision), dùng Either để:
  //   • Không mất data: error records vẫn được thu thập
  //   • Testable: assert trực tiếp trên Left/Right
  // ==========================================================================

  "PATTERN 4 — Either error handling" should {

    "tách đúng số records thành công và thất bại" in {
      val lines = Seq(
        csvLine("P001"), // valid ✓
        "NOT,ENOUGH,FIELDS", // parse error ✗
        csvLine("P003")
          .replace("vana@example.com", "bad-email"), // validation error ✗
        csvLine("P004") // valid ✓
      )

      // Pipeline giữ cả hai nhánh bằng cách fold vào tuple
      val (oks, errs) = Source(lines)
        .map(CsvParser.parseLine)
        .map {
          case Right(r) => BookingValidator.validate(r)
          case left     => left
        }
        .runWith(
          Sink.fold((List.empty[BookingRecord], List.empty[StreamError])) {
            case ((acc, errs), Right(r)) => (acc :+ r, errs)
            case ((acc, errs), Left(e))  => (acc, errs :+ e)
          }
        )
        .futureValue

      oks.map(_.passengerId) shouldBe Seq("P001", "P004")
      errs should have size 2
    }

    "divertTo: ghi error sang nhánh khác, success qua nhánh chính" in {
      val lines = Seq(
        csvLine("P001"), // valid
        "BAD", // error
        csvLine("P003") // valid
      )

      val errorBuffer = List.newBuilder[StreamError]
      val successBuffer = List.newBuilder[String]

      // divertTo cho phép chia stream mà không cần GraphDSL
      Source(lines)
        .map(CsvParser.parseLine)
        .divertTo(
          that = Flow[Either[StreamError, BookingRecord]]
            .map(_.left.get)
            .to(Sink.foreach(e => errorBuffer += e)),
          when = _.isLeft
        )
        .collect { case Right(r) => r }
        .runWith(Sink.foreach(r => successBuffer += r.passengerId))
        .futureValue

      successBuffer.result() shouldBe List("P001", "P003")
      errorBuffer.result() should have size 1
    }
  }

  // ==========================================================================
  // PATTERN 5 — groupBy + fold: batch theo nhóm
  // ==========================================================================
  //
  //  groupBy tạo SubFlow riêng cho mỗi key.
  //  fold ở mỗi SubFlow tổng hợp nội bộ.
  //  mergeSubstreams gom kết quả về 1 stream.
  // ==========================================================================

  "PATTERN 5 — groupBy và aggregation" should {

    "nhóm theo routeKey và đếm đúng số records mỗi nhóm" in {
      val records = Seq(
        record("P1", "HAN", "SGN"),
        record("P2", "HAN", "SGN"),
        record("P3", "SGN", "HAN"),
        record("P4", "HAN", "SGN"),
        record("P5", "DAD", "SGN")
      )

      val countByRoute = Source(records)
        .groupBy(maxSubstreams = 10, _.routeKey)
        .fold(("", 0)) { case ((_, n), r) => (r.routeKey, n + 1) }
        .mergeSubstreams
        .runWith(Sink.seq)
        .futureValue
        .toMap

      countByRoute("HAN_SGN") shouldBe 3
      countByRoute("SGN_HAN") shouldBe 1
      countByRoute("DAD_SGN") shouldBe 1
    }

    "scan: emit running total sau mỗi element" in {
      // scan khác fold: emit sau MỖI element, không đợi stream kết thúc
      val runningTotals = Source(Seq(1, 2, 3, 4, 5))
        .scan(0)(_ + _)
        .runWith(Sink.seq)
        .futureValue

      // scan bao gồm cả giá trị seed ban đầu (0)
      runningTotals shouldBe Seq(0, 1, 3, 6, 10, 15)
    }

    "grouped: tạo micro-batch kích thước n" in {
      val batches = Source(1 to 10)
        .grouped(3)
        .runWith(Sink.seq)
        .futureValue

      batches should have size 4 // 3+3+3+1
      batches.head shouldBe Seq(1, 2, 3)
      batches.last shouldBe Seq(10) // batch cuối có thể nhỏ hơn
    }
  }

  // ==========================================================================
  // PATTERN 6 — Supervision strategy
  // ==========================================================================
  //
  //  Supervision quyết định stream làm gì khi 1 stage throw exception:
  //   • Stop   (default) — stream fail ngay lập tức
  //   • Resume           — bỏ qua element gây lỗi, stream tiếp tục
  //   • Restart          — reset state của stage, stream tiếp tục
  //
  //  withAttributes(ActorAttributes.supervisionStrategy(decider)) áp supervision
  //  cho toàn bộ RunnableGraph phía sau.
  // ==========================================================================

  "PATTERN 6 — Supervision strategy" should {

    "Resume: bỏ qua element gây NumberFormatException, giữ elements còn lại" in {
      val decider: Supervision.Decider = {
        case _: NumberFormatException => Supervision.Resume
        case _                        => Supervision.Stop
      }

      val result = Source(Seq("1", "abc", "3", "!!!", "5"))
        .map(_.toInt) // "abc" và "!!!" → NumberFormatException
        .withAttributes(ActorAttributes.supervisionStrategy(decider))
        .runWith(Sink.seq)

      result.futureValue shouldBe Seq(1, 3, 5)
    }

    "Stop (default): stream fail khi stage throw exception" in {
      val resultFuture = Source(Seq("1", "abc", "3"))
        .map(_.toInt) // "abc" → NumberFormatException → stream fail
        .runWith(Sink.seq)

      // Future failed → dùng .failed.futureValue để lấy exception ra
      resultFuture.failed.futureValue shouldBe a[NumberFormatException]
    }

    "Restart: reset state nhưng stream tiếp tục" in {
      // Dùng stateful stage có thể reset — đây demo bằng counter đơn giản
      var stateCounter = 0
      val decider: Supervision.Decider = {
        case _: IllegalArgumentException =>
          stateCounter = 0 // reset side-effect state giả lập
          Supervision.Restart
        case _ => Supervision.Stop
      }

      val result = Source(Seq(1, 2, -1, 3)) // -1 → exception
        .map { n =>
          if (n < 0) throw new IllegalArgumentException(s"Negative: $n")
          else { stateCounter += 1; n * 10 }
        }
        .withAttributes(ActorAttributes.supervisionStrategy(decider))
        .runWith(Sink.seq)

      // -1 bị bỏ qua (Restart); 1,2,3 được xử lý
      result.futureValue shouldBe Seq(10, 20, 30)
    }
  }
}

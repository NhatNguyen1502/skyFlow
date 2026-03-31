# Hướng dẫn Viết Test cho Akka Streams

## Mục lục

1. [Tổng quan & Triết lý](#1-tổng-quan--triết-lý)
2. [Thiết lập (Setup)](#2-thiết-lập-setup)
3. [Pattern 1 — Unit Test (không cần stream)](#3-pattern-1--unit-test-không-cần-stream)
4. [Pattern 2 — Sink.seq + futureValue](#4-pattern-2--sinkseq--futurevalue)
5. [Pattern 3 — Test Flow thực tế](#5-pattern-3--test-flow-thực-tế)
6. [Pattern 4 — TestSource.probe + TestSink.probe](#6-pattern-4--testsourceprobe--testsinkprobe)
7. [Pattern 5 — Either stream (success / failure)](#7-pattern-5--either-stream-success--failure)
8. [Pattern 6 — groupBy, fold, scan](#8-pattern-6--groupby-fold-scan)
9. [Pattern 7 — Supervision Strategy](#9-pattern-7--supervision-strategy)
10. [Các lỗi phổ biến](#10-các-lỗi-phổ-biến)
11. [Checklist trước khi commit](#11-checklist-trước-khi-commit)

---

## 1. Tổng quan & Triết lý

### Phân tầng test

Một pipeline Akka Streams thường gồm 3 lớp logic độc lập. Hãy test từng lớp riêng biệt:

```
┌──────────────────────────────┬────────────────────────────┬──────────────────┐
│ Lớp                          │ Ví dụ trong dự án          │ Loại test        │
├──────────────────────────────┼────────────────────────────┼──────────────────┤
│ Logic thuần túy (pure)       │ CsvParser, BookingValidator│ Unit test        │
│ Stage / Flow                 │ parseFlow, validateFlow    │ Integration test │
│ Pipeline hoàn chỉnh (end2end)│ ErrorHandlingService       │ Integration test │
└──────────────────────────────┴────────────────────────────┴──────────────────┘
```

**Quy tắc**: Logic thuần túy (pure functions) → unit test bình thường, **không cần** `ActorSystem`. Chỉ khi cần chạy stream thật sự mới cần `ActorSystem`.

### Dependencies trong `build.sbt`

```scala
// Bắt buộc cho test Akka Streams
"com.typesafe.akka" %% "akka-stream-testkit"      % akkaVersion % Test,
"com.typesafe.akka" %% "akka-actor-testkit-typed"  % akkaVersion % Test,
"org.scalatest"     %% "scalatest"                 % "3.2.17"    % Test
```

---

## 2. Thiết lập (Setup)

### `src/test/resources/application.conf`

Tắt log Akka khi chạy test để output gọn hơn:

```hocon
akka {
  loglevel = "OFF"
  stdout-loglevel = "OFF"
  log-dead-letters = off
  log-dead-letters-during-shutdown = off
  actor.warn-about-java-serializer-usage = off
}
```

### Boilerplate cho test có stream

```scala
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._   // toClassic extension
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

class MyStreamSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures {

  private val testKit = ActorTestKit()

  // ⚠️ Dùng classic ActorSystem làm implicit DUY NHẤT:
  //   1. TestSource/TestSink.probe yêu cầu akka.actor.ActorSystem (classic)
  //   2. Akka Streams tự derive Materializer từ classic system
  //   3. Tránh ambiguity nếu có cả typed + classic trong scope
  implicit val system: akka.actor.ActorSystem = testKit.system.toClassic

  // Timeout khi dùng futureValue / failed.futureValue
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()   // giải phóng ActorSystem sau khi suite xong
    super.afterAll()
  }
}
```

> **Tại sao `toClassic`?**  
> `akka-stream-testkit` (TestSource/TestSink) vẫn dùng API cổ điển (classic).  
> `actorTestKit.system` trả về `ActorSystem[Nothing]` (typed) — cần gọi `.toClassic`  
> để lấy `akka.actor.ActorSystem` tương thích. Import `adapter._` cung cấp extension này.

---

## 3. Pattern 1 — Unit Test (không cần stream)

Dùng cho các hàm pure như parser, validator. **Không cần** `ActorSystem`, `Materializer` hay bất kỳ import Akka nào.

```scala
// CsvParserSpec.scala
class CsvParserSpec extends AnyWordSpec with Matchers {

  "CsvParser.parseLine" when {

    "nhận dòng hợp lệ" should {
      "trả về Right(BookingRecord)" in {
        CsvParser.parseLine("P001,Nguyen Van A,a@b.com,HAN,SGN,2") shouldBe
          Right(BookingRecord("P001", "Nguyen Van A", "a@b.com", "HAN", "SGN", 2))
      }
    }

    "nhận dòng sai số cột" should {
      "trả về Left(CsvParseError)" in {
        val result = CsvParser.parseLine("ONLY,3,FIELDS")
        result shouldBe a[Left[_, _]]
        result.left.get.message should include("Expected 6 fields")
      }
    }
  }
}
```

**Khi nào dùng Pattern này?**
- Logic không phụ thuộc vào Actor, Stream, hay async
- Parse, validate, transform, calculate — tất cả là pure function
- Chạy nhanh nhất, dễ debug nhất

---

## 4. Pattern 2 — `Sink.seq` + `futureValue`

Cách phổ biến nhất để test stream: chạy stream, thu toàn bộ kết quả vào `Seq`, rồi assert.

```scala
"map và filter trên Source" in {
  val result = Source(1 to 10)
    .filter(_ % 2 == 0)
    .map(_ * 10)
    .runWith(Sink.seq)        // ← trả về Future[Seq[Int]]

  result.futureValue shouldBe Seq(20, 40, 60, 80, 100)
  //     ^^^^^^^^^^^
  //     ScalaFutures block & unwrap Future tự động
}
```

### Các Sink thông dụng

| Mục đích | Code |
|---|---|
| Thu tất cả vào List | `runWith(Sink.seq)` → `Future[Seq[T]]` |
| Tổng hợp 1 giá trị | `runWith(Sink.fold(zero)(f))` → `Future[R]` |
| Đếm | `runWith(Sink.fold(0)((n, _) => n + 1))` |
| Chỉ lấy kết quả đầu tiên | `runWith(Sink.head)` → `Future[T]` |
| Bỏ kết quả, lấy `Done` | `runWith(Sink.ignore)` → `Future[Done]` |

### Assert stream rỗng

```scala
Source.empty[Int].runWith(Sink.seq).futureValue shouldBe empty
```

### Assert stream fail

```scala
// ❌ SAI — thrownBy không bắt được lỗi từ Future thất bại
a[NumberFormatException] should be thrownBy result.futureValue

// ✅ ĐÚNG — dùng .failed.futureValue để lấy exception ra
result.failed.futureValue shouldBe a[NumberFormatException]
```

---

## 5. Pattern 3 — Test Flow thực tế

Thay thế nguồn dữ liệu bằng `Source(Seq(...))` để tránh cần file I/O. Định nghĩa `Flow` tường minh để dễ tái sử dụng trong test khác.

```scala
"CsvParser flow parse đúng 2 dòng" in {
  val lines = Seq(
    "P001,Name,a@b.com,HAN,SGN,2",
    "P002,Name,c@d.com,SGN,HAN,1"
  )

  // Định nghĩa Flow có kiểu rõ ràng — type annotation giúp compiler
  val parseFlow: Flow[String, Either[StreamError, BookingRecord], NotUsed] =
    Flow[String].map(CsvParser.parseLine)

  val results = Source(lines)
    .via(parseFlow)           // ← inject Flow vào Source
    .runWith(Sink.seq)
    .futureValue

  results should have size 2
  results.forall(_.isRight) shouldBe true
}
```

### Pipeline nhiều stage

```scala
"pipeline đầy đủ: chỉ giữ records hợp lệ" in {
  val lines = Seq(
    "P001,Name,a@b.com,HAN,SGN,2",  // ✓ valid
    "BAD,LINE",                        // ✗ parse error
    "P003,Name,a@b.com,HAN,SGN,99",  // ✗ validation error
    "P004,Name,a@b.com,DAD,SGN,1"    // ✓ valid
  )

  val validIds = Source(lines)
    .map(CsvParser.parseLine)
    .collect { case Right(r) => r }    // bỏ parse errors
    .map(BookingValidator.validate)
    .collect { case Right(r) => r }    // bỏ validation errors
    .map(_.passengerId)
    .runWith(Sink.seq)
    .futureValue

  validIds shouldBe Seq("P001", "P004")
}
```

---

## 6. Pattern 4 — `TestSource.probe` + `TestSink.probe`

Dùng khi cần kiểm soát từng bước: đẩy element thủ công, kiểm tra backpressure, hay giả lập upstream failure.

```
TestSource.probe[T]   →  "faucet" — bạn vặn bao nhiêu thì chảy bấy nhiêu
TestSink.probe[T]     →  "bucket" — bạn kéo thì mới nhận được
```

### Lifecycle cơ bản

```scala
val (pub, sub) = TestSource.probe[Int]
  .via(myFlow)
  .toMat(TestSink.probe[Int])(Keep.both)
  .run()
//       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//       Keep.both giữ materialized value của cả source (pub) và sink (sub)

sub.request(2)       // 1. Kéo demand: "tôi muốn nhận 2 element"
pub.sendNext(10)     // 2. Đẩy element (chỉ được sau khi có demand)
pub.sendNext(20)
sub.expectNext(10)   // 3. Assert element nhận được
sub.expectNext(20)

pub.sendComplete()   // 4. Kết thúc stream
sub.expectComplete()
```

### ⚠️ Gotcha quan trọng: thứ tự `request` và `sendNext`

Akka Streams là **pull-based** (Reactive Streams spec). `pub.sendNext()` sẽ **block** cho đến khi subscriber có demand. Vi phạm thứ tự này sẽ gây timeout trong test:

```scala
// ❌ SAI — sendNext(30) block vô thời hạn vì sub chỉ request(2)
sub.request(2)
pub.sendNext(10)
pub.sendNext(20)
pub.sendNext(30)     // ← BLOCK! Chưa có demand cho element thứ 3

// ✅ ĐÚNG — request trước, send sau
sub.request(2)
pub.sendNext(10)
pub.sendNext(20)
sub.expectNext(10, 20)
sub.expectNoMessage()   // xác nhận không có gì pending

sub.request(1)          // request thêm
pub.sendNext(30)        // bây giờ mới send được
sub.expectNext(30)
```

### Giả lập upstream error

```scala
val (pub, sub) = TestSource.probe[String]
  .toMat(TestSink.probe[String])(Keep.both)
  .run()

sub.request(1)
pub.sendError(new RuntimeException("boom"))
sub.expectError()    // ← chỉ assert error xảy ra, không check type
// hoặc:
// sub.expectError(myException)   // ← assert đúng exception instance
```

### Stream rỗng — phải `request` trước

```scala
// ❌ SAI — sendComplete trước khi subscription sẵn sàng
pub.sendComplete()
sub.expectComplete()   // fail vì nhận được OnSubscribe thay vì OnComplete

// ✅ ĐÚNG — request(1) để khởi động subscription, rồi mới complete
sub.request(1)
pub.sendComplete()
sub.expectComplete()
```

---

## 7. Pattern 5 — Either stream (success / failure)

Tốt hơn Supervision vì: không mất data, explict, dễ test.

```scala
"tách success và failure" in {
  val lines = Seq(
    "P001,Name,a@b.com,HAN,SGN,2",   // valid ✓
    "BAD,LINE",                        // error ✗
    "P003,Name,a@b.com,HAN,SGN,0"    // validation error ✗
  )

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

  oks.map(_.passengerId) shouldBe Seq("P001")
  errs should have size 2
}
```

### `divertTo` — chia 2 nhánh không cần GraphDSL

```scala
val errorBuf   = List.newBuilder[StreamError]
val successBuf = List.newBuilder[String]

Source(lines)
  .map(CsvParser.parseLine)
  .divertTo(
    that = Flow[Either[StreamError, BookingRecord]]
      .map(_.left.get)
      .to(Sink.foreach(e => errorBuf += e)),
    when = _.isLeft           // điều kiện rẽ nhánh
  )
  .collect { case Right(r) => r }
  .runWith(Sink.foreach(r => successBuf += r.passengerId))
  .futureValue

successBuf.result() shouldBe List("P001", "P003")
errorBuf.result()   should have size 1
```

---

## 8. Pattern 6 — `groupBy`, `fold`, `scan`

### `groupBy` + `fold` + `mergeSubstreams`

```scala
"đếm records theo routeKey" in {
  val records = Seq(
    BookingRecord("P1", ..., "HAN", "SGN", 2),
    BookingRecord("P2", ..., "HAN", "SGN", 1),
    BookingRecord("P3", ..., "SGN", "HAN", 3)
  )

  val countByRoute = Source(records)
    .groupBy(maxSubstreams = 10, _.routeKey)   // chia theo key
    .fold(("", 0)) { case ((_, n), r) => (r.routeKey, n + 1) }
    .mergeSubstreams                           // gom lại
    .runWith(Sink.seq)
    .futureValue
    .toMap

  countByRoute("HAN_SGN") shouldBe 2
  countByRoute("SGN_HAN") shouldBe 1
}
```

> **Lưu ý**: `maxSubstreams` phải ≥ số key thực tế. Nếu vượt quá, stream sẽ fail với `TooManySubstreamsOpenException`.

### `fold` vs `scan` vs `reduce`

| Operator | Emit khi nào | Zero value | Xử lý stream rỗng |
|---|---|---|---|
| `fold(zero)(f)` | 1 lần sau khi stream kết thúc | Cần cung cấp | Trả về `zero` |
| `reduce(f)` | 1 lần sau khi stream kết thúc | Không cần | Fail với `NoSuchElementException` |
| `scan(zero)(f)` | Sau **mỗi** element (bao gồm seed) | Cần cung cấp | Trả về chỉ `Seq(zero)` |

```scala
// scan: Seq bắt đầu bằng seed value (0)
Source(Seq(1, 2, 3, 4, 5))
  .scan(0)(_ + _)
  .runWith(Sink.seq)
  .futureValue shouldBe Seq(0, 1, 3, 6, 10, 15)
  //                     ^^^
  //                     seed value luôn là phần tử đầu tiên
```

### `grouped` — micro-batch

```scala
// grouped(n): emit Seq[T] với kích thước tối đa n
Source(1 to 7)
  .grouped(3)
  .runWith(Sink.seq)
  .futureValue shouldBe Seq(Seq(1,2,3), Seq(4,5,6), Seq(7))
  //                                                 ^^^^^
  //                                                 batch cuối có thể nhỏ hơn n
```

---

## 9. Pattern 7 — Supervision Strategy

Supervision quyết định stream làm gì khi một stage **throw exception**:

| Strategy | Hành vi | Khi nào dùng |
|---|---|---|
| `Stop` (default) | Stream fail ngay | Lỗi nghiêm trọng, không thể bỏ qua |
| `Resume` | Bỏ qua element lỗi, tiếp tục | Input có thể xấu nhưng stream phải chạy tiếp |
| `Restart` | Reset state stage, tiếp tục | Stage có state cần reset sau lỗi |

### Test `Resume`

```scala
"Resume: bỏ qua element lỗi" in {
  val decider: Supervision.Decider = {
    case _: NumberFormatException => Supervision.Resume
    case _                        => Supervision.Stop
  }

  val result = Source(Seq("1", "abc", "3", "!!!", "5"))
    .map(_.toInt)
    .withAttributes(ActorAttributes.supervisionStrategy(decider))
    .runWith(Sink.seq)

  result.futureValue shouldBe Seq(1, 3, 5)
}
```

### Test `Stop` (stream fail)

```scala
"Stop: stream fail và Future bị rejected" in {
  val resultFuture = Source(Seq("1", "abc", "3"))
    .map(_.toInt)
    .runWith(Sink.seq)

  // ✅ Dùng .failed.futureValue để lấy exception từ Future thất bại
  resultFuture.failed.futureValue shouldBe a[NumberFormatException]

  // ❌ ĐỪNG dùng cách này — thrownBy không bắt được lỗi async
  // a[NumberFormatException] should be thrownBy resultFuture.futureValue
}
```

### Áp supervision cho toàn pipeline

```scala
val result = source
  .via(parseFlow)
  .via(validateFlow)
  .via(enrichFlow)
  // ← đặt ở đây, áp dụng cho toàn bộ phía trên
  .withAttributes(ActorAttributes.supervisionStrategy(decider))
  .runWith(Sink.seq)
```

---

## 10. Các lỗi phổ biến

### `value sendNext is not a member of Any`

**Nguyên nhân**: `Keep.both` trả về `(Any, Any)` vì compiler không infer được type.

```scala
// ❌ SAI
val (pub, sub) = TestSource.probe[Int]
  .toMat(TestSink.probe[Int])(Keep.both)
  .run()

// ✅ ĐÚNG — annotate type tường minh
val (pub, sub): (TestPublisher.Probe[Int], TestSubscriber.Probe[Int]) =
  TestSource.probe[Int]
    .toMat(TestSink.probe[Int])(Keep.both)
    .run()
```

> Nếu vẫn lỗi, hãy dùng cách destructure ngay tại điểm khai báo với `val (pub: TestPublisher.Probe[Int], sub: TestSubscriber.Probe[Int]) = ...`

### `could not find implicit value for parameter system: akka.actor.ActorSystem`

**Nguyên nhân**: TestSource/TestSink cần classic `ActorSystem`, không phải typed.

```scala
// ❌ SAI — typed system không đủ
implicit val system: akka.actor.typed.ActorSystem[Nothing] = testKit.system

// ✅ ĐÚNG — convert sang classic
implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
```

### `A Materializer is required`

**Nguyên nhân**: Xảy ra khi có 2 implicit `ActorSystem` (typed + classic) trong scope — compiler không biết dùng cái nào để derive `Materializer`.

**Giải pháp**: Chỉ giữ **một** implicit `ActorSystem` duy nhất (classic).

### `assertion failed: timeout during expectMsg: expecting request() signal`

**Nguyên nhân**: Gọi `pub.sendNext()` trước khi `sub.request(n)`.

```scala
// ✅ ĐÚNG — luôn request trước
sub.request(n)
pub.sendNext(elem)
```

### `expected OnComplete, found OnSubscribe`

**Nguyên nhân**: Gọi `pub.sendComplete()` trước khi subscriber sẵn sàng.

```scala
// ✅ ĐÚNG — request(1) để khởi động subscription trước
sub.request(1)
pub.sendComplete()
sub.expectComplete()
```

### Test timeout chậm/ngẫu nhiên

- Tăng `patienceConfig` nếu CI chậm
- Kiểm tra có `afterAll()` gọi `shutdownTestKit()` không — thiếu thì ActorSystem bị leak giữa các suite
- Với `groupBy`, đảm bảo stream kết thúc (upstream `complete`) trước khi assert

---

## 11. Checklist trước khi commit

```
□ Unit test (pure logic) KHÔNG import Akka — chạy nhanh, không có side-effect
□ Mỗi test class có StreamSpec dùng 1 implicit ActorSystem (classic)
□ afterAll() gọi testKit.shutdownTestKit()
□ src/test/resources/application.conf tắt Akka logging
□ TestSource/TestSink: request(n) trước sendNext
□ Stream fail → assert bằng .failed.futureValue, không dùng thrownBy
□ groupBy: maxSubstreams >= số key thực tế
□ scan: kết quả Seq bắt đầu bằng seed value
□ Chạy sbt test — tất cả pass trên CI
```

---

## Tham khảo nhanh (Quick Reference)

```scala
// Chạy và thu kết quả
Source(list).via(flow).runWith(Sink.seq).futureValue

// Chạy và tổng hợp
Source(list).runWith(Sink.fold(zero)(f)).futureValue

// Assert lỗi async
stream.failed.futureValue shouldBe a[SomeException]

// Kiểm soát từng bước
val (pub, sub) = TestSource.probe[T].via(flow).toMat(TestSink.probe[T])(Keep.both).run()
sub.request(n) ; pub.sendNext(x) ; sub.expectNext(x)
pub.sendComplete() ; sub.expectComplete()

// Supervision
.withAttributes(ActorAttributes.supervisionStrategy {
  case _: MyException => Supervision.Resume
  case _              => Supervision.Stop
})
```

---

*Xem code ví dụ đầy đủ: [StreamPipelineSpec.scala](../src/test/scala/com/skyflow/streaming/StreamPipelineSpec.scala)*

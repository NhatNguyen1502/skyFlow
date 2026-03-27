# Akka Streams — Toàn bộ API Reference

> Tài liệu tham khảo đầy đủ các API trong Akka Streams (v2.6.x).
> Mỗi API đều có **signature**, **giải thích**, **ví dụ code**, và **khi nào dùng**.

---

## Mục lục

1. [Khái niệm cốt lõi](#1-khái-niệm-cốt-lõi)
2. [Source — Nguồn dữ liệu](#2-source--nguồn-dữ-liệu)
3. [Flow — Biến đổi dữ liệu](#3-flow--biến-đổi-dữ-liệu)
4. [Sink — Đích đến dữ liệu](#4-sink--đích-đến-dữ-liệu)
5. [Operators trên Source/Flow](#5-operators-trên-sourceflow)
   - 5.1 [Transformation (biến đổi)](#51-transformation-biến-đổi)
   - 5.2 [Filtering (lọc)](#52-filtering-lọc)
   - 5.3 [Grouping (gom nhóm)](#53-grouping-gom-nhóm)
   - 5.4 [Aggregation (tổng hợp)](#54-aggregation-tổng-hợp)
   - 5.5 [Async / Parallel](#55-async--parallel)
   - 5.6 [Time-based](#56-time-based)
   - 5.7 [Combining Sources](#57-combining-sources)
   - 5.8 [Error handling](#58-error-handling)
   - 5.9 [Watching & Monitoring](#59-watching--monitoring)
   - 5.10 [Backpressure & Buffer](#510-backpressure--buffer)
6. [Materialization & Keep](#6-materialization--keep)
7. [Graph DSL — Xây dựng graph phức tạp](#7-graph-dsl--xây-dựng-graph-phức-tạp)
8. [Fan-out Stages](#8-fan-out-stages)
9. [Fan-in Stages](#9-fan-in-stages)
10. [SubStreams](#10-substreams)
11. [File IO](#11-file-io)
12. [Actor ↔ Stream Integration](#12-actor--stream-integration)
13. [Supervision & Restart](#13-supervision--restart)
14. [Testing APIs](#14-testing-apis)
15. [Tổng hợp: Bảng so sánh nhanh](#15-tổng-hợp-bảng-so-sánh-nhanh)

---

## 1. Khái niệm cốt lõi

### Type Signature cơ bản

```
Source[+Out, +Mat]   — Tạo ra elements kiểu Out, materialized value kiểu Mat
Flow[-In, +Out, +Mat] — Nhận In, phát ra Out, materialized value kiểu Mat
Sink[-In, +Mat]      — Tiêu thụ elements kiểu In, materialized value kiểu Mat
```

### Reactive Streams roles

| Akka Streams  | Reactive Streams | Vai trò                            |
|---------------|------------------|------------------------------------|
| `Source`      | Publisher        | Phát ra elements                   |
| `Flow`        | Processor        | Nhận vào → biến đổi → phát ra     |
| `Sink`        | Subscriber       | Tiêu thụ elements                  |
| `RunnableGraph` | —              | Graph đã đóng, sẵn sàng chạy      |

### Vòng đời của một Stream

```
1. Xây dựng (blueprint):  Source ~> Flow ~> Sink    (chưa chạy, chỉ là mô tả)
2. Materialization:        graph.run()               (tạo actors nội bộ, bắt đầu chạy)
3. Chạy:                   elements chảy qua stages  (backpressure tự động)
4. Kết thúc:               upstream complete / error  (resources được giải phóng)
```

### Ví dụ đầy đủ nhất

```scala
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream._
import scala.concurrent.Future

implicit val system: ActorSystem = ActorSystem("demo")
implicit val ec = system.dispatcher

val source: Source[Int, NotUsed] = Source(1 to 100)
val flow: Flow[Int, String, NotUsed] = Flow[Int].map(i => s"Number: $i")
val sink: Sink[String, Future[Done]] = Sink.foreach[String](println)

// Cách 1: via + runWith
val result: Future[Done] = source.via(flow).runWith(sink)

// Cách 2: toMat + run
val result2: Future[Done] = source.via(flow).toMat(sink)(Keep.right).run()
```

---

## 2. Source — Nguồn dữ liệu

### 2.1 Tạo Source từ collection

```scala
// Từ List, Seq, Range
Source(List(1, 2, 3))              // Source[Int, NotUsed]
Source(1 to 1000)                  // Source[Int, NotUsed]
Source(Vector("a", "b", "c"))      // Source[String, NotUsed]
```

### 2.2 Source đơn lẻ

```scala
Source.single("hello")             // Phát ra đúng 1 element rồi complete
Source.empty[Int]                  // Complete ngay lập tức, không phát gì
Source.failed(new Exception("!"))  // Fail ngay lập tức
```

### 2.3 Source lặp

```scala
Source.repeat("ping")              // Vô hạn: "ping", "ping", "ping", ...
Source.cycle(() => List(1,2,3).iterator)  // Vô hạn: 1,2,3,1,2,3,...

// Tạo element theo logic
Source.unfold(0) { state =>        // unfold: tạo element + state mới
  if (state > 100) None            // None → complete
  else Some((state + 1, state))    // Some((nextState, emittedElement))
}
// Phát ra: 0, 1, 2, ..., 100

Source.unfoldAsync(0) { state =>   // Giống unfold nhưng trả Future
  Future.successful(
    if (state > 100) None
    else Some((state + 1, state))
  )
}
```

### 2.4 Source từ Future / Publisher bên ngoài

```scala
// Từ Future
Source.future(Future.successful(42))        // Phát ra 1 element khi Future complete
Source.lazyFuture(() => Future(loadData())) // Lazy: chỉ tạo Future khi có demand

// Từ callback
Source.fromIterator(() => myIterator)       // Từ Iterator (lazy evaluation)

// Source.queue — push thủ công từ bên ngoài stream
val (queue, done) = Source.queue[String](bufferSize = 100, OverflowStrategy.backpressure)
  .toMat(Sink.foreach(println))(Keep.both)
  .run()

queue.offer("hello")   // => Future[QueueOfferResult]
queue.offer("world")
queue.complete()        // Signal complete stream
```

### 2.5 Source tick (periodic)

```scala
// Phát element mỗi khoảng thời gian
Source.tick(
  initialDelay = 1.second,
  interval = 5.seconds,
  tick = "heartbeat"
)
// Phát "heartbeat" mỗi 5 giây, sau delay ban đầu 1 giây
```

### 2.6 Source.maybe — Complete thủ công

```scala
val (promise, done) = Source.maybe[Int]
  .toMat(Sink.foreach(println))(Keep.both)
  .run()

promise.trySuccess(Some(42))    // Phát 42 rồi complete
// hoặc
promise.trySuccess(None)         // Complete mà không phát gì
```

### 2.7 Source.lazily — Deferred creation

```scala
// Source chỉ được tạo khi có downstream demand
Source.lazily(() => Source(expensiveComputation()))
```

### 2.8 Source.actorRef (Classic) & ActorSource (Typed)

```scala
// Classic API
val ref: ActorRef = Source.actorRef[String](
  completionMatcher = { case "done" => },
  failureMatcher = PartialFunction.empty,
  bufferSize = 100,
  overflowStrategy = OverflowStrategy.dropHead
).to(Sink.foreach(println)).run()

ref ! "message1"
ref ! "message2"
ref ! "done"  // complete stream

// Typed API
import akka.stream.typed.scaladsl.ActorSource
val source: Source[String, ActorRef[String]] = ActorSource.actorRef[String](
  completionMatcher = { case "done" => },
  failureMatcher = PartialFunction.empty,
  bufferSize = 100,
  overflowStrategy = OverflowStrategy.dropHead
)
```

### Bảng tóm tắt Source

| API                        | Mô tả                                 | Mat value                    |
|----------------------------|----------------------------------------|------------------------------|
| `Source(collection)`       | Từ collection có sẵn                   | `NotUsed`                    |
| `Source.single(elem)`      | Đúng 1 element                         | `NotUsed`                    |
| `Source.empty`             | Không gì cả, complete ngay             | `NotUsed`                    |
| `Source.failed(ex)`        | Fail ngay                              | `NotUsed`                    |
| `Source.repeat(elem)`      | Vô hạn lặp lại 1 element              | `NotUsed`                    |
| `Source.cycle(iterFn)`     | Vô hạn lặp qua iterator               | `NotUsed`                    |
| `Source.tick(init,int,el)` | Periodic element                       | `Cancellable`                |
| `Source.future(f)`         | 1 element từ Future                    | `NotUsed`                    |
| `Source.unfold(s)(fn)`     | Stateful element generation            | `NotUsed`                    |
| `Source.queue(size,strat)` | Push thủ công qua queue                | `SourceQueueWithComplete`    |
| `Source.maybe`             | Gửi 0-1 element qua Promise           | `Promise[Option[T]]`         |
| `Source.fromIterator(fn)`  | Từ Iterator (lazy)                     | `NotUsed`                    |
| `Source.lazily(fn)`        | Deferred Source creation               | `Future[Mat]`                |
| `Source.actorRef(...)`     | Push qua actor ref                     | `ActorRef[T]`                |
| `ActorSource.actorRef(...)` | Push qua typed actor ref             | `ActorRef[T]`                |

---

## 3. Flow — Biến đổi dữ liệu

### 3.1 Tạo Flow

```scala
// Flow cơ bản
val flow: Flow[Int, String, NotUsed] = Flow[Int].map(_.toString)

// Flow identity (pass-through)
val passThrough: Flow[Int, Int, NotUsed] = Flow[Int]

// Flow từ function
val fromFunction: Flow[Int, String, NotUsed] = Flow.fromFunction[Int, String](_.toString)
```

### 3.2 Kết nối

```scala
val flow1: Flow[Int, String, NotUsed] = Flow[Int].map(_.toString)
val flow2: Flow[String, Int, NotUsed] = Flow[String].map(_.length)

// via: kết nối flow, giữ mat value bên trái
val combined: Flow[Int, Int, NotUsed] = flow1.via(flow2)

// viaMat: kết nối flow, chọn mat value
val combined2 = flow1.viaMat(flow2)(Keep.right)
```

---

## 4. Sink — Đích đến dữ liệu

### 4.1 Sink cơ bản

```scala
// Sink.foreach — thực hiện side effect cho mỗi element
val printSink: Sink[String, Future[Done]] = Sink.foreach[String](println)

// Sink.ignore — bỏ qua tất cả, chỉ quan tâm completion
val ignoreSink: Sink[Any, Future[Done]] = Sink.ignore

// Sink.head — lấy element đầu tiên
val headSink: Sink[Int, Future[Int]] = Sink.head

// Sink.headOption — lấy element đầu tiên (Option)
val headOptSink: Sink[Int, Future[Option[Int]]] = Sink.headOption

// Sink.last — lấy element cuối cùng
val lastSink: Sink[Int, Future[Int]] = Sink.last

// Sink.lastOption
val lastOptSink: Sink[Int, Future[Option[Int]]] = Sink.lastOption
```

### 4.2 Sink aggregation

```scala
// Sink.fold — gom tất cả thành 1 giá trị
val sumSink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)
// Kết quả: Future chứa tổng tất cả elements

// Sink.reduce — giống fold nhưng không cần initial value
val maxSink: Sink[Int, Future[Int]] = Sink.reduce[Int](math.max)

// Sink.seq — thu thập tất cả vào Seq
val collectSink: Sink[Int, Future[Seq[Int]]] = Sink.seq[Int]
// ⚠️ Cẩn thận: load toàn bộ vào memory!
```

### 4.3 Sink.actorRef — gửi đến Actor

```scala
// Classic
val sink = Sink.actorRef[String](
  ref = myActor,
  onCompleteMessage = "stream-complete",
  onFailureMessage = (ex: Throwable) => s"stream-failed: ${ex.getMessage}"
)

// Typed — ActorSink
import akka.stream.typed.scaladsl.ActorSink
val sink = ActorSink.actorRef[String](
  ref = typedActorRef,
  onCompleteMessage = "done",
  onFailureMessage = ex => s"failed: $ex"
)
```

### 4.4 Sink đặc biệt

```scala
// Sink.cancelled — cancel upstream ngay lập tức
val cancelSink: Sink[Any, NotUsed] = Sink.cancelled

// Sink.onComplete — chỉ quan tâm success/failure
val completionSink: Sink[Any, Future[Done]] = Sink.onComplete {
  case Success(_)  => println("Stream completed!")
  case Failure(ex) => println(s"Stream failed: $ex")
}

// Sink.queue — pull elements theo demand
val sink: Sink[Int, SinkQueueWithCancel[Int]] = Sink.queue[Int]()
```

### Bảng tóm tắt Sink

| API                    | Mô tả                          | Mat value                     |
|------------------------|---------------------------------|-------------------------------|
| `Sink.foreach(fn)`     | Side effect cho từng element    | `Future[Done]`                |
| `Sink.ignore`          | Bỏ qua tất cả                  | `Future[Done]`                |
| `Sink.head`            | Element đầu tiên                | `Future[T]`                   |
| `Sink.last`            | Element cuối cùng               | `Future[T]`                   |
| `Sink.fold(z)(fn)`     | Fold thành 1 giá trị           | `Future[T]`                   |
| `Sink.reduce(fn)`      | Fold không cần initial value    | `Future[T]`                   |
| `Sink.seq`             | Thu thập vào Seq                | `Future[Seq[T]]`              |
| `Sink.actorRef(ref,…)` | Gửi đến actor                  | `NotUsed`                     |
| `Sink.cancelled`       | Cancel upstream ngay            | `NotUsed`                     |
| `Sink.queue()`         | Pull thủ công                   | `SinkQueueWithCancel[T]`      |

---

## 5. Operators trên Source/Flow

> Các operator dưới đây có thể dùng trên cả `Source` và `Flow`.
> Ví dụ: `Source(1 to 10).map(...)` hoặc `Flow[Int].map(...)`

### 5.1 Transformation (biến đổi)

#### `map` — Biến đổi 1:1

```scala
Source(1 to 5).map(_ * 2)
// Output: 2, 4, 6, 8, 10
```

#### `mapConcat` — Biến đổi 1:N (flatMap)

```scala
Source(List("hello world", "foo bar"))
  .mapConcat(str => str.split(" ").toList)
// Output: "hello", "world", "foo", "bar"
```

#### `statefulMapConcat` — 1:N với state

```scala
// Đánh số thứ tự cho mỗi element
Source(List("a", "b", "c"))
  .statefulMapConcat { () =>
    var counter = 0
    element => {
      counter += 1
      List(s"$counter: $element")
    }
  }
// Output: "1: a", "2: b", "3: c"

// Ví dụ: Deduplicate — chỉ emit element chưa thấy
Source(List(1, 2, 2, 3, 1, 3, 4))
  .statefulMapConcat { () =>
    var seen = Set.empty[Int]
    element => {
      if (seen.contains(element)) Nil      // đã thấy → không emit
      else {
        seen += element
        List(element)                       // chưa thấy → emit
      }
    }
  }
// Output: 1, 2, 3, 4
```

> **Khi nào dùng**: Khi cần transformation mà phụ thuộc vào trạng thái trước đó
> (đếm, lọc trùng, window logic tùy chỉnh, ...)

#### `flatMapConcat` — FlatMap giữ thứ tự

```scala
Source(1 to 3).flatMapConcat { i =>
  Source(List(s"${i}a", s"${i}b"))
}
// Output: "1a", "1b", "2a", "2b", "3a", "3b" (giữ thứ tự)
```

#### `flatMapMerge` — FlatMap không giữ thứ tự (parallel)

```scala
Source(1 to 3).flatMapMerge(breadth = 3) { i =>
  Source(List(s"${i}a", s"${i}b"))
}
// Output: có thể "1a", "2a", "1b", "3a", "2b", "3b" (interleaved)
```

> **So sánh**: `flatMapConcat` = sequential, `flatMapMerge` = parallel (nhanh hơn)

#### `collect` — map + filter (dùng partial function)

```scala
Source(1 to 10).collect {
  case n if n % 2 == 0 => n * 10
}
// Output: 20, 40, 60, 80, 100  (chỉ số chẵn, nhân 10)
```

> **Rất hữu ích** với `Either`: `.collect { case Right(v) => v }`

#### `mapWithResource` — Map với resource lifecycle

```scala
// Mỗi upstream element được xử lý với resource (file, DB connection, ...)
// Resource được tạo khi stream bắt đầu và đóng khi stream kết thúc
Flow[String].mapWithResource(() => new FileWriter("output.txt"))(
  (writer, element) => { writer.write(element); element },
  writer => { writer.close(); None }
)
```

#### `scan` — Fold với emit mỗi bước

```scala
Source(1 to 5).scan(0)(_ + _)
// Output: 0, 1, 3, 6, 10, 15
// (emit giá trị tích lũy tại MỖI bước, bao gồm initial value)
```

> **So sánh fold**: `fold` chỉ emit 1 giá trị cuối, `scan` emit mỗi bước.

#### `scanAsync` — Scan bất đồng bộ

```scala
Source(1 to 5).scanAsync(0) { (acc, elem) =>
  Future.successful(acc + elem)
}
// Output: 0, 1, 3, 6, 10, 15 (giống scan nhưng async)
```

#### `sliding` — Sliding window

```scala
Source(1 to 5).sliding(n = 3, step = 1)
// Output: Seq(1,2,3), Seq(2,3,4), Seq(3,4,5)

Source(1 to 6).sliding(n = 2, step = 2)
// Output: Seq(1,2), Seq(3,4), Seq(5,6)
```

#### `log` — Debug logging

```scala
Source(1 to 5)
  .log("my-stream")                    // Log mỗi element tại mức DEBUG
  .log("my-stream", elem => s"Got: $elem")  // Custom extract function
  .withAttributes(
    Attributes.logLevels(
      onElement = Attributes.LogLevels.Info,
      onFinish = Attributes.LogLevels.Info,
      onFailure = Attributes.LogLevels.Error
    )
  )
```

---

### 5.2 Filtering (lọc)

#### `filter` — Lọc theo điều kiện

```scala
Source(1 to 10).filter(_ % 2 == 0)
// Output: 2, 4, 6, 8, 10
```

#### `filterNot` — Lọc ngược

```scala
Source(1 to 10).filterNot(_ % 2 == 0)
// Output: 1, 3, 5, 7, 9
```

#### `take` / `takeWhile`

```scala
Source(1 to 100).take(5)               // Output: 1, 2, 3, 4, 5 → complete
Source(1 to 100).takeWhile(_ < 5)      // Output: 1, 2, 3, 4 → complete (stop khi false)

// inclusive = true: bao gồm cả element đầu tiên false
Source(1 to 100).takeWhile(_ < 5, inclusive = true)  // Output: 1, 2, 3, 4, 5
```

#### `drop` / `dropWhile`

```scala
Source(1 to 10).drop(3)                // Output: 4, 5, 6, 7, 8, 9, 10
Source(1 to 10).dropWhile(_ < 5)       // Output: 5, 6, 7, 8, 9, 10
```

#### `distinct` — Lọc trùng (toàn bộ stream, ⚠️ memory)

```scala
// ⚠️ Không có sẵn trong Akka Streams!
// Dùng statefulMapConcat thay thế (xem ví dụ ở phần 5.1)
```

#### `deduplicate` — Lọc trùng liên tiếp

```scala
// Lọc elements trùng liên tiếp (consecutive duplicates)
Source(List(1, 1, 2, 2, 2, 3, 1)).statefulMapConcat { () =>
  var prev: Option[Int] = None
  element => {
    if (prev.contains(element)) Nil
    else { prev = Some(element); List(element) }
  }
}
// Output: 1, 2, 3, 1
```

---

### 5.3 Grouping (gom nhóm)

#### `grouped` — Gom cố định N elements

```scala
Source(1 to 10).grouped(3)
// Output: Seq(1,2,3), Seq(4,5,6), Seq(7,8,9), Seq(10)
```

#### `groupedWithin` — Gom theo size HOẶC thời gian

```scala
Source(1 to 1000)
  .throttle(10, 1.second)
  .groupedWithin(n = 100, d = 5.seconds)
// Gom tối đa 100 elements, HOẶC sau 5 giây (cái nào đến trước)
// Rất hữu ích cho batching + timeout
```

> **Use case**: Batch insert vào DB — gom 100 records hoặc mỗi 2 giây

#### `groupedWeighted` — Gom theo trọng số

```scala
// Gom elements cho đến khi tổng weight >= maxWeight
Source(List("ab", "cde", "f", "ghij"))
  .groupedWeighted(maxWeight = 5)(_.length.toLong)
// Output: Seq("ab", "cde"), Seq("f", "ghij")
// ("ab"=2 + "cde"=3 = 5 ≥ 5 → emit), ("f"=1 + "ghij"=4 = 5 ≥ 5 → emit)
```

#### `groupBy` — Chia thành SubStreams

```scala
// Chia stream thành substreams theo key function
// maxSubstreams BẮT BUỘC — giới hạn số substream đồng thời
Source(1 to 10)
  .groupBy(maxSubstreams = 3, _ % 3)    // key: 0, 1, 2
  .map(n => s"Group ${n % 3}: $n")
  .mergeSubstreams                        // gom lại thành 1 stream
// Output (thứ tự không đảm bảo):
// "Group 1: 1", "Group 2: 2", "Group 0: 3", "Group 1: 4", ...

// ⚠️ PHẢI gọi mergeSubstreams hoặc concatSubstreams ở cuối!
// ⚠️ Nếu maxSubstreams < số key thực tế → stream sẽ bị kẹt (deadlock)!
```

---

### 5.4 Aggregation (tổng hợp)

#### `fold` — Gom tất cả thành 1 giá trị (emit khi complete)

```scala
Source(1 to 10).fold(0)(_ + _)
// Output: 55  (chỉ emit 1 lần khi upstream complete)
```

#### `foldAsync` — Fold bất đồng bộ

```scala
Source(1 to 10).foldAsync(0) { (acc, elem) =>
  Future.successful(acc + elem)
}
// Output: 55
```

#### `reduce` — Giống fold, không cần initial value

```scala
Source(1 to 10).reduce(_ + _)
// Output: 55
// ⚠️ Fail nếu stream rỗng! Dùng fold nếu stream có thể rỗng.
```

#### `scan` vs `fold` vs `reduce` — So sánh

```scala
Source(1 to 5).scan(0)(_ + _)      // emit: 0, 1, 3, 6, 10, 15  (emit MỌI bước + init)
Source(1 to 5).fold(0)(_ + _)      // emit: 15                   (emit 1 lần cuối)
Source(1 to 5).reduce(_ + _)       // emit: 15                   (emit 1 lần cuối, no init)
```

| Operator  | Emit khi nào          | Cần initial value? | Stream rỗng?       |
|-----------|-----------------------|--------------------|---------------------|
| `scan`    | Mỗi element + init   | Có                 | Emit initial value  |
| `fold`    | Khi upstream complete | Có                 | Emit initial value  |
| `reduce`  | Khi upstream complete | Không              | ⚠️ Fail!           |

---

### 5.5 Async / Parallel

#### `mapAsync` — Map bất đồng bộ (giữ thứ tự)

```scala
Source(1 to 100).mapAsync(parallelism = 4) { n =>
  Future {
    // Xử lý nặng: DB query, HTTP call, ...
    Thread.sleep(100)
    n * 2
  }
}
// Chạy tối đa 4 Futures đồng thời
// Output giữ nguyên thứ tự: 2, 4, 6, ...
```

#### `mapAsyncUnordered` — Map bất đồng bộ (không giữ thứ tự)

```scala
Source(1 to 100).mapAsyncUnordered(parallelism = 4) { n =>
  Future {
    Thread.sleep(Random.nextInt(100))
    n * 2
  }
}
// Chạy tối đa 4 Futures đồng thời
// Output KHÔNG giữ thứ tự — nhanh hơn mapAsync vì không phải đợi
```

> **Khi nào dùng gì:**
> - `mapAsync`: Khi thứ tự quan trọng (ghi file, hiển thị cho user)
> - `mapAsyncUnordered`: Khi chỉ quan tâm throughput (batch processing, metrics)

#### `async` — Boundary giữa các stages

```scala
Source(1 to 100)
  .map(_ * 2)          // Stage A
  .async               // ← async boundary: A và B chạy trên thread khác nhau
  .map(_ + 1)          // Stage B
  .runWith(Sink.ignore)

// Mặc định: tất cả stages chạy trên cùng 1 actor (fused)
// .async tách ra → có thể chạy parallel → nhưng thêm overhead message passing
```

> **Khi nào dùng**: Khi có CPU-intensive stage muốn chạy parallel với IO-intensive stage

---

### 5.6 Time-based

#### `throttle` — Giới hạn throughput

```scala
Source(1 to 1000)
  .throttle(elements = 10, per = 1.second)
// Phát tối đa 10 elements/giây

Source(1 to 1000)
  .throttle(elements = 10, per = 1.second, maximumBurst = 20)
// Cho phép burst tối đa 20 elements, rồi quay về 10/giây

// Throttle theo "cost" (trọng số)
Source(List("a", "abc", "abcdef"))
  .throttle(cost = 10, per = 1.second, costCalculation = _.length)
// Giới hạn 10 ký tự/giây (không phải 10 elements)
```

#### `delay` — Trì hoãn mỗi element

```scala
Source(1 to 10).delay(500.millis)
// Mỗi element bị delay 500ms

Source(1 to 10).delay(500.millis, DelayOverflowStrategy.backpressure)
// DelayOverflowStrategy: backpressure, dropHead, dropTail, dropBuffer, dropNew, emitEarly, fail
```

#### `idleTimeout` — Fail nếu không có element trong khoảng thời gian

```scala
Source.tick(1.second, 1.second, "tick")
  .idleTimeout(3.seconds)
// Nếu > 3 giây không có element → stream fail với TimeoutException
```

#### `backpressureTimeout` — Fail nếu bị backpressure quá lâu

```scala
source.backpressureTimeout(5.seconds)
// Nếu upstream bị backpressure > 5 giây → fail
```

#### `initialTimeout` — Fail nếu element đầu tiên không đến kịp

```scala
source.initialTimeout(3.seconds)
// Nếu > 3 giây vẫn chưa có element đầu tiên → fail
```

#### `completionTimeout` — Fail nếu stream chưa complete trong thời gian

```scala
source.completionTimeout(30.seconds)
// Toàn bộ stream phải complete trong 30 giây
```

#### `keepAlive` — Inject element khi idle

```scala
Source.tick(5.seconds, 5.seconds, "data")
  .keepAlive(max = 1.second, () => "heartbeat")
// Nếu > 1 giây không có element → inject "heartbeat"
```

#### `takeWithin` / `dropWithin`

```scala
Source.tick(100.millis, 100.millis, "tick")
  .takeWithin(1.second)    // Lấy elements trong 1 giây đầu, rồi complete

Source.tick(100.millis, 100.millis, "tick")
  .dropWithin(500.millis)  // Bỏ elements trong 500ms đầu
```

---

### 5.7 Combining Sources

#### `concat` — Nối nối tiếp

```scala
val s1 = Source(1 to 3)
val s2 = Source(4 to 6)

s1.concat(s2)
// Output: 1, 2, 3, 4, 5, 6 (s2 bắt đầu sau khi s1 complete)
```

#### `merge` — Trộn xen kẽ (không đảm bảo thứ tự)

```scala
s1.merge(s2)
// Output: có thể 1, 4, 2, 5, 3, 6 (interleaved)
```

#### `mergePreferred` — Merge với priority

```scala
s1.mergePreferred(s2, preferred = true)
// s2 được ưu tiên hơn khi cả hai cùng có element
```

#### `zip` — Ghép cặp

```scala
val names = Source(List("Alice", "Bob"))
val ages = Source(List(25, 30))

names.zip(ages)
// Output: ("Alice", 25), ("Bob", 30)
// Stream complete khi BẤT KỲ source nào complete
```

#### `zipWith` — Ghép cặp + transform

```scala
names.zipWith(ages)((name, age) => s"$name is $age")
// Output: "Alice is 25", "Bob is 30"
```

#### `zipWithIndex` — Đánh index

```scala
Source(List("a", "b", "c")).zipWithIndex
// Output: ("a", 0), ("b", 1), ("c", 2)
```

#### `interleave` — Xen kẽ có kiểm soát

```scala
s1.interleave(s2, segmentSize = 2)
// Output: 1, 2, 4, 5, 3, 6 (lấy 2 từ s1, 2 từ s2, luân phiên)
```

#### `orElse` — Fallback source

```scala
Source.empty[Int].orElse(Source(List(1, 2, 3)))
// Output: 1, 2, 3 (dùng s2 khi s1 rỗng)
```

#### `prepend` — Thêm vào đầu

```scala
Source(4 to 6).prepend(Source(1 to 3))
// Output: 1, 2, 3, 4, 5, 6
```

---

### 5.8 Error handling

#### `recover` — Catch error, emit 1 element fallback

```scala
Source(List(1, 2, 3))
  .map { n =>
    if (n == 2) throw new RuntimeException("boom")
    else n
  }
  .recover {
    case _: RuntimeException => -1
  }
// Output: 1, -1 (stream complete sau recover)
```

#### `recoverWith` — Catch error, switch sang source khác

```scala
Source(List(1, 2, 3))
  .map { n =>
    if (n == 2) throw new RuntimeException("boom")
    else n
  }
  .recoverWith {
    case _: RuntimeException => Source(List(99, 100))
  }
// Output: 1, 99, 100
```

#### `recoverWithRetries` — Retry N lần

```scala
Source(List(1, 2, 3))
  .map(riskyOperation)
  .recoverWithRetries(
    attempts = 3,
    { case _: RuntimeException => Source(List(-1)) }
  )
```

#### `mapError` — Transform error type

```scala
source.mapError {
  case ex: IOException => new ServiceException("IO failed", ex)
}
```

#### `divertTo` — Chuyển hướng elements thỏa điều kiện

```scala
val errorSink = Sink.foreach[Int](n => println(s"Error: $n"))

Source(1 to 10)
  .divertTo(errorSink, _ > 5)   // elements > 5 đi vào errorSink
  .runWith(Sink.foreach(n => println(s"OK: $n")))
// Main: 1, 2, 3, 4, 5
// Error: 6, 7, 8, 9, 10
```

---

### 5.9 Watching & Monitoring

#### `watchTermination` — Callback khi stream kết thúc

```scala
Source(1 to 10)
  .watchTermination() { (mat, terminationFuture) =>
    terminationFuture.onComplete {
      case Success(_)  => println("Stream done!")
      case Failure(ex) => println(s"Stream failed: $ex")
    }
    mat
  }
  .runWith(Sink.ignore)
```

#### `monitor` — Element counter

```scala
val (monitor, done) = Source(1 to 100)
  .monitor
  .toMat(Sink.ignore)(Keep.both)
  .run()
// monitor cho phép theo dõi số elements đi qua
```

#### `alsoTo` — Tee: gửi copy đến sink phụ

```scala
Source(1 to 10)
  .alsoTo(Sink.foreach(n => println(s"Side: $n")))  // side sink
  .runWith(Sink.fold(0)(_ + _))                      // main sink
// Side sink nhận copy, main sink nhận tổng
```

#### `wireTap` — Giống alsoTo nhưng không tạo backpressure

```scala
Source(1 to 10)
  .wireTap(Sink.foreach(n => println(s"Tap: $n")))
  .runWith(Sink.ignore)
// Nếu tap sink chậm → elements bị drop (không block main stream)
```

---

### 5.10 Backpressure & Buffer

#### `buffer` — Đệm giữa stages

```scala
source.buffer(size = 1000, overflowStrategy = OverflowStrategy.backpressure)
```

**Tất cả OverflowStrategy:**

| Strategy                         | Hành vi khi buffer đầy                |
|----------------------------------|---------------------------------------|
| `OverflowStrategy.backpressure`  | Slow down upstream (default, an toàn) |
| `OverflowStrategy.dropHead`      | Bỏ element cũ nhất trong buffer       |
| `OverflowStrategy.dropTail`      | Bỏ element mới nhất trong buffer      |
| `OverflowStrategy.dropBuffer`    | Xóa toàn bộ buffer                   |
| `OverflowStrategy.dropNew`       | Bỏ element incoming                   |
| `OverflowStrategy.fail`          | Fail stream ngay lập tức              |

#### `conflate` — Gom khi consumer chậm

```scala
// Gom elements thành summary khi downstream chậm
Source.tick(10.millis, 10.millis, 1)
  .conflate(_ + _)                    // gom bằng cách cộng
  .throttle(1, 1.second)
// Nếu upstream phát 100 elements/giây, consumer xử lý 1/giây
// → conflate gom ~100 elements thành 1 số (sum)
```

#### `conflateWithSeed` — Conflate với seed function

```scala
Source.tick(10.millis, 10.millis, "msg")
  .conflateWithSeed(msg => List(msg))((list, msg) => msg :: list)
// Gom messages vào List khi consumer chậm
```

#### `extrapolate` — Tạo thêm khi producer chậm

```scala
// Khi upstream chậm → lặp lại element cuối
Source.tick(1.second, 1.second, "data")
  .extrapolate(element => Iterator.continually(element + "-repeat"))
  .throttle(10, 1.second)
// Producer phát 1/giây, consumer muốn 10/giây
// → extrapolate tạo thêm copies
```

#### `expand` — Giống extrapolate nhưng flexible hơn

```scala
Source.tick(1.second, 1.second, "data")
  .expand(element => Iterator.continually(element))
// Luôn có element sẵn cho downstream (không bao giờ backpressure upstream)
```

---

## 6. Materialization & Keep

### Materialized Value là gì?

Khi stream được `run()`, mỗi stage tạo ra một **materialized value** — giá trị "phụ" ngoài data stream.

```scala
// Source[Int, NotUsed]                 → Mat = NotUsed (không có gì hữu ích)
// Sink.fold[Int, Int](0)(_ + _)       → Mat = Future[Int] (kết quả fold)
// Sink.foreach(println)               → Mat = Future[Done] (completion signal)
// FileIO.fromPath(path)               → Mat = Future[IOResult] (bytes read)
// Source.queue(100, ...)              → Mat = SourceQueueWithComplete (queue reference)
// Source.actorRef(...)                → Mat = ActorRef (actor reference)
```

### Keep — Chọn materialized value nào

Khi connect 2 stages, phải chọn giữ Mat value bên trái hay bên phải:

```scala
val source: Source[Int, NotUsed] = Source(1 to 10)
val sink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

// via/to mặc định: Keep.left (giữ mat phía trái)
source.to(sink)               // RunnableGraph[NotUsed]  ← mat của source

// toMat: chọn tùy ý
source.toMat(sink)(Keep.left)   // RunnableGraph[NotUsed]      ← mat của source
source.toMat(sink)(Keep.right)  // RunnableGraph[Future[Int]]   ← mat của sink
source.toMat(sink)(Keep.both)   // RunnableGraph[(NotUsed, Future[Int])]
source.toMat(sink)(Keep.none)   // RunnableGraph[NotUsed]

// Shortcut: runWith giữ mat phía "other side"
source.runWith(sink)            // Future[Int]  ← tự động Keep.right
sink.runWith(source)            // NotUsed      ← tự động Keep.right (từ perspective của sink)
```

### Bảng Keep

| Method                       | Giữ Mat nào        | Ví dụ kết quả                |
|------------------------------|---------------------|------------------------------|
| `source.to(sink)`           | Left (source)       | `NotUsed`                    |
| `source.toMat(sink)(Keep.left)` | Left            | `NotUsed`                    |
| `source.toMat(sink)(Keep.right)` | Right           | `Future[Int]`                |
| `source.toMat(sink)(Keep.both)` | Both             | `(NotUsed, Future[Int])`     |
| `source.toMat(sink)(Keep.none)` | Neither          | `NotUsed`                    |
| `source.runWith(sink)`      | Right (sink)        | `Future[Int]`                |
| `source.via(flow)`          | Left (source)       | Giữ mat của source           |
| `source.viaMat(flow)(Keep.right)` | Right          | Giữ mat của flow             |

### Ví dụ thực tế

```scala
// Muốn lấy IOResult khi ghi file xong
val source: Source[ByteString, Future[IOResult]] = FileIO.fromPath(inputPath)
val sink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(outputPath)

// ❌ Sai: giữ mat bên trái (IOResult của đọc, không phải ghi)
source.to(sink).run()

// ✅ Đúng: Keep.right → lấy IOResult của ghi file
source.toMat(sink)(Keep.right).run()

// ✅ Hoặc: Keep.both → lấy cả hai
val (readResult, writeResult) = source.toMat(sink)(Keep.both).run()
readResult.onComplete(r => println(s"Read: $r"))
writeResult.onComplete(r => println(s"Write: $r"))
```

---

## 7. Graph DSL — Xây dựng graph phức tạp

### Khi nào cần Graph DSL?

- Khi pipeline có **nhiều hơn 1 input hoặc output**
- Khi cần **fan-out** (broadcast, balance, partition)
- Khi cần **fan-in** (merge, zip, concat)
- Khi cần **cycles** (feedback loops)

### Basic Pattern

```scala
import akka.stream.scaladsl.GraphDSL
import akka.stream.{ClosedShape, FlowShape, SourceShape, SinkShape}

// RunnableGraph (ClosedShape) — tất cả ports đã connect
val graph: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(
  GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    // Thêm stages
    val source = builder.add(Source(1 to 10))
    val flow = builder.add(Flow[Int].map(_ * 2))
    val sink = builder.add(Sink.foreach[Int](println))

    // Connect
    source ~> flow ~> sink

    ClosedShape
  }
)
graph.run()
```

### Shapes

| Shape           | Inputs | Outputs | Dùng làm gì                |
|-----------------|--------|---------|-----------------------------|
| `ClosedShape`   | 0      | 0       | RunnableGraph (sẵn sàng run) |
| `SourceShape`   | 0      | 1       | Custom Source               |
| `SinkShape`     | 1      | 0       | Custom Sink                 |
| `FlowShape`     | 1      | 1       | Custom Flow                 |
| `FanOutShape`   | 1      | N       | 1 input, N outputs         |
| `FanInShape`    | N      | 1       | N inputs, 1 output         |
| `BidiShape`     | 2      | 2       | Bidirectional (protocol)    |
| `UniformFanOutShape` | 1 | N       | Broadcast, Balance, etc     |
| `UniformFanInShape`  | N | 1       | Merge, Concat, etc          |

### Tạo custom reusable graph

```scala
// Custom Source (SourceShape)
val mySource: Graph[SourceShape[Int], NotUsed] = GraphDSL.create() { implicit b =>
  import GraphDSL.Implicits._
  val s1 = b.add(Source(1 to 5))
  val s2 = b.add(Source(6 to 10))
  val merge = b.add(Merge[Int](2))
  s1 ~> merge
  s2 ~> merge
  SourceShape(merge.out)
}
Source.fromGraph(mySource).runWith(Sink.foreach(println))

// Custom Flow (FlowShape)
val myFlow: Graph[FlowShape[Int, String], NotUsed] = GraphDSL.create() { implicit b =>
  import GraphDSL.Implicits._
  val broadcast = b.add(Broadcast[Int](2))
  val merge = b.add(Merge[String](2))

  broadcast.out(0).map(n => s"even: $n").filter(_ => true) ~> merge
  broadcast.out(1).map(n => s"odd: $n").filter(_ => true)  ~> merge

  FlowShape(broadcast.in, merge.out)
}
Source(1 to 10).via(Flow.fromGraph(myFlow)).runWith(Sink.foreach(println))
```

### Connect operators

```scala
import GraphDSL.Implicits._

source ~> flow ~> sink        // connect tuần tự
source ~> broadcast            // source → fan-out
broadcast ~> sink1             // fan-out → sink
broadcast.out(0) ~> sink1     // explicit outlet index
merge ~> sink                  // fan-in → sink
```

### Graph DSL với Materialized Values

```scala
// GraphDSL.create(sink1, sink2)((mat1, mat2) => ...) — combine mat values
val graph = RunnableGraph.fromGraph(
  GraphDSL.createGraph(fileSink, countSink)((io, count) => (io, count)) { 
    implicit builder => (fSink, cSink) =>
      import GraphDSL.Implicits._
      val broadcast = builder.add(Broadcast[ByteString](2))
      
      source ~> broadcast
      broadcast ~> fSink
      broadcast ~> Flow[ByteString].map(_ => 1) ~> cSink
      
      ClosedShape
  }
)
val (ioResult, count): (Future[IOResult], Future[Int]) = graph.run()
```

---

## 8. Fan-out Stages

### `Broadcast[T]` — Clone đến N outputs

```scala
val broadcast = builder.add(Broadcast[Int](outputPorts = 3))
// Mỗi element được copy đến TẤT CẢ outputs
// ⚠️ Nếu 1 output chậm → tất cả bị backpressure

source ~> broadcast
broadcast.out(0) ~> sink1   // nhận copy
broadcast.out(1) ~> sink2   // nhận copy
broadcast.out(2) ~> sink3   // nhận copy
```

### `Balance[T]` — Round-robin distribution

```scala
val balance = builder.add(Balance[Int](outputPorts = 3))
// Mỗi element đi đến ĐÚNG 1 output (round-robin / fastest consumer)
// Dùng cho load balancing / parallel processing

source ~> balance
balance.out(0) ~> worker1 ~> merge
balance.out(1) ~> worker2 ~> merge
balance.out(2) ~> worker3 ~> merge
```

> **Broadcast vs Balance:**
> - Broadcast: MỌI consumer nhận MỌI element (clone)
> - Balance: MỖI element đi đến ĐÚNG 1 consumer (distribute)

### `Partition[T]` — Route theo điều kiện

```scala
val partition = builder.add(Partition[Int](outputPorts = 3, partitioner = n => n % 3))
// Element n → output port (n % 3)
// 0 → port 0, 1 → port 1, 2 → port 2, 3 → port 0, ...

// Ví dụ: Either split
val partition = builder.add(Partition[Either[Error, Result]](2, {
  case Left(_)  => 0   // errors → port 0
  case Right(_) => 1   // success → port 1
}))
```

### `UnzipWith` — Split thành nhiều streams

```scala
val unzip = builder.add(UnzipWith[String, Int, Boolean](
  str => (str.length, str.nonEmpty)
))
// Input: "hello" → Output: (5, true) trên 2 outlets riêng

source ~> unzip.in
unzip.out0 ~> intSink      // nhận lengths: 5, 3, ...
unzip.out1 ~> boolSink     // nhận nonEmpty: true, true, ...
```

### `Unzip` — Split tuple

```scala
val unzip = builder.add(Unzip[String, Int]())
// Input: ("Alice", 25) → out0: "Alice", out1: 25
```

---

## 9. Fan-in Stages

### `Merge[T]` — Gộp N inputs (interleaved)

```scala
val merge = builder.add(Merge[Int](inputPorts = 3))
// Gom elements từ 3 inputs → 1 output
// Thứ tự: không đảm bảo (first-come first-served)

source1 ~> merge
source2 ~> merge
source3 ~> merge
merge ~> sink
```

### `MergePreferred[T]` — Merge ưu tiên

```scala
val merge = builder.add(MergePreferred[Int](secondaryPorts = 2))
// merge.preferred: input ưu tiên (luôn được lấy trước)
// merge.in(0), merge.in(1): inputs phụ

prioritySource ~> merge.preferred
normalSource1  ~> merge.in(0)
normalSource2  ~> merge.in(1)
merge.out ~> sink
```

### `MergePrioritized[T]` — Merge theo trọng số

```scala
val merge = builder.add(MergePrioritized[Int](priorities = Seq(3, 1)))
// Port 0 có priority 3, port 1 có priority 1
// → Port 0 được chọn ~75% thời gian khi cả hai cùng sẵn sàng
```

### `MergeLatest[T]` — Emit khi bất kỳ input nào có element mới

```scala
val merge = builder.add(MergeLatest[Int](inputPorts = 2))
// Emit List chứa latest value từ mỗi input
// Input 0: 1      → emit List(1, 0)  (0 = initial)
// Input 1: 10     → emit List(1, 10)
// Input 0: 2      → emit List(2, 10)
```

### `Concat[T]` — Nối nối tiếp (không phải trộn)

```scala
val concat = builder.add(Concat[Int](inputPorts = 3))
// Source 1 complete → bắt đầu Source 2 → complete → Source 3
// Khác Merge: đảm bảo thứ tự tuần tự
```

### `Zip[A, B]` — Ghép cặp

```scala
val zip = builder.add(Zip[String, Int]())
// 2 inputs → 1 output chứa tuple (String, Int)
// Đợi CẢ HAI input có element → emit tuple
// Complete khi BẤT KỲ input complete

names ~> zip.in0
ages  ~> zip.in1
zip.out ~> sink     // nhận (String, Int)
```

### `ZipWith[A, B, C]` — Ghép cặp + transform

```scala
val zipWith = builder.add(ZipWith[String, Int, String](
  (name, age) => s"$name ($age)"
))

names ~> zipWith.in0
ages  ~> zipWith.in1
zipWith.out ~> sink   // nhận "Alice (25)", "Bob (30)"
```

### `ZipN[T]` — Ghép N inputs

```scala
val zipN = builder.add(ZipN[Int](n = 3))
// 3 inputs → output List[Int] (length 3)
```

### `ZipLatest[A, B]` — Ghép cặp từ latest values

```scala
val zipLatest = builder.add(ZipLatest[String, Int]())
// Giống Zip nhưng dùng latest value nếu 1 bên nhanh hơn
```

### Bảng so sánh Fan-in

| Stage               | Hành vi                                   | Thứ tự          |
|----------------------|-------------------------------------------|------------------|
| `Merge`             | Gom first-come                             | Không đảm bảo    |
| `MergePreferred`    | Gom, ưu tiên 1 input                      | Preferred trước   |
| `MergePrioritized`  | Gom, theo tỉ lệ priority                  | Theo weight       |
| `MergeLatest`       | Emit list latest values                    | Emit mỗi update   |
| `Concat`            | Nối tuần tự                                | Đảm bảo thứ tự   |
| `Zip`               | Ghép cặp, đợi cả hai                      | Synchronized      |
| `ZipWith`           | Ghép cặp + transform                      | Synchronized      |
| `ZipLatest`         | Ghép cặp latest                            | Latest wins       |

---

## 10. SubStreams

### `groupBy` — Chia thành SubStreams

```scala
Source(1 to 20)
  .groupBy(maxSubstreams = 5, _ % 5)     // key: 0, 1, 2, 3, 4
  .map(n => s"Group ${n % 5}: $n")
  .mergeSubstreams                         // gom lại
  .runWith(Sink.foreach(println))

// ⚠️ maxSubstreams phải >= số key thực tế, nếu không → deadlock!
// Mỗi substream là 1 stream độc lập có thể xử lý parallel
```

### `mergeSubstreams` — Gom SubStreams (interleaved)

```scala
source
  .groupBy(10, keyFn)
  .map(transform)
  .mergeSubstreams   // gom lại, thứ tự không đảm bảo
```

### `mergeSubstreamsWithParallelism` — Gom với parallelism

```scala
source
  .groupBy(10, keyFn)
  .map(transform)
  .mergeSubstreamsWithParallelism(4)   // tối đa 4 substreams chạy đồng thời
```

### `concatSubstreams` — Gom SubStreams (tuần tự)

```scala
source
  .groupBy(10, keyFn)
  .map(transform)
  .concatSubstreams   // gom lại, giữ thứ tự (tuần tự)
// ⚠️ Chậm hơn mergeSubstreams vì phải đợi từng substream complete
```

### `splitWhen` / `splitAfter` — Chia khi điều kiện đúng

```scala
// Tạo substream mới khi element thỏa điều kiện
Source(1 to 10)
  .splitWhen(_ % 3 == 0)    // split TRƯỚC element thỏa điều kiện
  .fold(List.empty[Int])((acc, n) => acc :+ n)
  .mergeSubstreams
// Groups: [1,2], [3,4,5], [6,7,8], [9,10]

Source(1 to 10)
  .splitAfter(_ % 3 == 0)   // split SAU element thỏa điều kiện
  .fold(List.empty[Int])((acc, n) => acc :+ n)
  .mergeSubstreams
// Groups: [1,2,3], [4,5,6], [7,8,9], [10]
```

### `flatMapConcat` vs `flatMapMerge` — SubStream flatten

```scala
// flatMapConcat: xử lý substream tuần tự
Source(1 to 3).flatMapConcat(i => Source(List(s"${i}a", s"${i}b")))
// Output: "1a", "1b", "2a", "2b", "3a", "3b"

// flatMapMerge: xử lý substream song song
Source(1 to 3).flatMapMerge(breadth = 3)(i => Source(List(s"${i}a", s"${i}b")))
// Output: interleaved (nhanh hơn)
```

---

## 11. File IO

### `FileIO.fromPath` — Đọc file

```scala
import akka.stream.scaladsl.FileIO
import java.nio.file.Paths

val source: Source[ByteString, Future[IOResult]] =
  FileIO.fromPath(Paths.get("data.csv"))
// Đọc file thành stream of ByteString chunks, streaming (không load cả file vào RAM)
// Mat value: Future[IOResult] — chứa bytes đã đọc + status

// Đọc từ offset
FileIO.fromPath(Paths.get("data.csv"), chunkSize = 8192, startPosition = 1024)
```

### `FileIO.toPath` — Ghi file

```scala
val sink: Sink[ByteString, Future[IOResult]] =
  FileIO.toPath(Paths.get("output.csv"))

// Ghi append
FileIO.toPath(Paths.get("output.csv"), options = Set(StandardOpenOption.APPEND))

// Ghi tạo mới
FileIO.toPath(Paths.get("output.csv"), options = Set(
  StandardOpenOption.WRITE,
  StandardOpenOption.CREATE,
  StandardOpenOption.TRUNCATE_EXISTING
))
```

### `Framing.delimiter` — Chia ByteString thành dòng

```scala
import akka.stream.scaladsl.Framing

val lineFlow: Flow[ByteString, ByteString, NotUsed] =
  Framing.delimiter(
    delimiter = ByteString("\n"),
    maximumFrameLength = 4096,          // max bytes per line
    allowTruncation = true              // cho phép dòng cuối không có \n
  )

// Full pipeline: đọc file → chia dòng → parse
FileIO.fromPath(Paths.get("data.csv"))
  .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
  .map(_.utf8String.trim)              // ByteString → String
  .drop(1)                              // bỏ header
  .map(parseCsvLine)
  .runWith(Sink.foreach(println))
```

### `Framing.lengthField` — Frame theo length prefix

```scala
// Khi protocol dùng length prefix (VD: TCP binary protocol)
Framing.lengthField(
  fieldLength = 4,           // 4 bytes cho length field
  fieldOffset = 0,
  maximumFrameLength = 65536,
  byteOrder = ByteOrder.BIG_ENDIAN
)
```

### `Compression` — Nén / Giải nén

```scala
import akka.stream.scaladsl.Compression

// Gzip
source.via(Compression.gzip)       // nén
source.via(Compression.gunzip())   // giải nén

// Deflate
source.via(Compression.deflate)
source.via(Compression.inflate())
```

### `StreamConverters` — Interop với Java IO

```scala
import akka.stream.scaladsl.StreamConverters

// InputStream → Source
val source: Source[ByteString, Future[IOResult]] =
  StreamConverters.fromInputStream(() => new FileInputStream("data.csv"))

// Source → OutputStream
val sink: Sink[ByteString, Future[IOResult]] =
  StreamConverters.fromOutputStream(() => new FileOutputStream("output.csv"))

// Source[ByteString] → InputStream (blocking)
val inputStream: InputStream =
  Source.single(ByteString("hello")).runWith(StreamConverters.asInputStream())
```

---

## 12. Actor ↔ Stream Integration

### 12.1 mapAsync + ask — Pattern phổ biến nhất

```scala
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

implicit val timeout: Timeout = 5.seconds

val actorFlow: Flow[BookingRecord, AllocationResult, NotUsed] =
  Flow[BookingRecord].mapAsync(parallelism = 4) { booking =>
    flightActor.ask[FlightResponse] { replyTo =>
      AllocateSeats(booking.flightId, booking.seats, replyTo)
    }
  }
```

### 12.2 ActorSource — Actor push → Stream

```scala
import akka.stream.typed.scaladsl.ActorSource

val source: Source[Event, ActorRef[Event]] = ActorSource.actorRef[Event](
  completionMatcher = { case Event.Complete => },
  failureMatcher = { case Event.Fail(ex) => ex },
  bufferSize = 256,
  overflowStrategy = OverflowStrategy.dropHead
)

val actorRef: ActorRef[Event] = source.to(Sink.foreach(println)).run()
actorRef ! Event.Data("hello")
actorRef ! Event.Complete
```

### 12.3 ActorSink — Stream → Actor

```scala
import akka.stream.typed.scaladsl.ActorSink

val sink: Sink[String, NotUsed] = ActorSink.actorRef[String](
  ref = myActor,
  onCompleteMessage = "done",
  onFailureMessage = ex => s"failed: $ex"
)
```

### 12.4 ActorFlow — Actor as Flow (ask-based)

```scala
import akka.stream.typed.scaladsl.ActorFlow

val flow: Flow[Request, Response, NotUsed] = ActorFlow.ask[Request, Response](
  ref = myActor
)(timeout = 5.seconds, (request, replyTo) => MyCommand(request, replyTo))
```

### 12.5 Source.queue — Push interface

```scala
val (queue, done) = Source.queue[Int](
  bufferSize = 100,
  overflowStrategy = OverflowStrategy.backpressure
)
  .map(_ * 2)
  .toMat(Sink.foreach(println))(Keep.both)
  .run()

// Push từ bất kỳ đâu (HTTP handler, actor, timer, ...)
queue.offer(1)   // Future[QueueOfferResult]
queue.offer(2)
queue.offer(3)
queue.complete() // Signal complete

// QueueOfferResult:
// Enqueued          — thành công
// Dropped           — buffer đầy + dropNew/dropHead/dropTail/dropBuffer
// QueueClosed       — queue đã complete/fail
// Failure(cause)    — lỗi
```

### 12.6 Sink.actorRefWithBackpressure — Backpressure-aware

```scala
// Actor gửi Ack sau khi xử lý mỗi element → stream chờ Ack trước khi gửi tiếp
import akka.stream.typed.scaladsl.ActorSink

val sink = ActorSink.actorRefWithBackpressure(
  ref = processorActor,
  onInitMessage = InitMessage,
  ackMessage = AckMessage,
  onCompleteMessage = CompleteMessage,
  onFailureMessage = ex => FailureMessage(ex)
)
```

---

## 13. Supervision & Restart

### 13.1 Supervision Strategy — Xử lý exception trong stage

```scala
import akka.stream.{ActorAttributes, Supervision}

val decider: Supervision.Decider = {
  case _: ArithmeticException => Supervision.Resume    // bỏ qua element lỗi
  case _: NullPointerException => Supervision.Restart  // reset stage state
  case _                       => Supervision.Stop     // dừng stream (default)
}

Source(List(1, 0, 3, 0, 5))
  .map(100 / _)   // ArithmeticException khi chia cho 0
  .withAttributes(ActorAttributes.supervisionStrategy(decider))
  .runWith(Sink.foreach(println))
// Output: 100, 33, 20  (bỏ qua elements gây lỗi)
```

| Strategy    | Hành vi                                          |
|-------------|--------------------------------------------------|
| `Resume`    | Bỏ qua element lỗi, tiếp tục với element tiếp   |
| `Restart`   | Reset internal state, tiếp tục stream            |
| `Stop`      | Dừng toàn bộ stream (default)                    |

> **Resume vs Restart**: `Resume` giữ state, `Restart` reset state.
> VD: `scan(0)(_ + _)` nếu Restart → counter reset về 0.

### 13.2 RestartSource / RestartSink / RestartFlow — Auto-restart

```scala
import akka.stream.RestartSettings
import akka.stream.scaladsl.{RestartSource, RestartSink, RestartFlow}

val settings = RestartSettings(
  minBackoff = 1.second,
  maxBackoff = 30.seconds,
  randomFactor = 0.2              // jitter ±20%
)
  .withMaxRestarts(count = 10, within = 1.minute)

// RestartSource: restart source khi fail
val restartableSource: Source[String, NotUsed] = RestartSource.withBackoff(settings) { () =>
  sourceWhichMayFail()
}

// RestartSource.onFailuresOnly: chỉ restart khi fail (không restart khi complete)
val restartOnFail: Source[String, NotUsed] = RestartSource.onFailuresOnly(settings) { () =>
  sourceWhichMayFail()
}

// RestartSink: restart sink khi fail
val restartableSink: Sink[String, NotUsed] = RestartSink.withBackoff(settings) { () =>
  sinkWhichMayFail()
}

// RestartFlow: restart flow khi fail
val restartableFlow: Flow[String, String, NotUsed] = RestartFlow.withBackoff(settings) { () =>
  flowWhichMayFail()
}
```

### 13.3 recover / recoverWith — Element-level recovery

```scala
// recover: catch error, emit 1 fallback element, complete
source.map(riskyOp).recover {
  case _: IOException => defaultValue
}

// recoverWith: catch error, switch to fallback source
source.map(riskyOp).recoverWith {
  case _: IOException => Source.single(fallbackValue)
}

// recoverWithRetries: retry N times
source.map(riskyOp).recoverWithRetries(attempts = 3, {
  case _: IOException => Source(backupData)
})
```

---

## 14. Testing APIs

### TestSource & TestSink

```scala
import akka.stream.testkit.scaladsl.{TestSource, TestSink}

// TestSink — kiểm tra output
val probe = Source(1 to 5)
  .map(_ * 2)
  .runWith(TestSink.probe[Int])

probe.request(3)                   // demand 3 elements
probe.expectNext(2, 4, 6)         // kiểm tra
probe.request(2)
probe.expectNext(8, 10)
probe.expectComplete()

// TestSource — kiểm tra input handling
val (pub, sub) = TestSource.probe[Int]
  .map(_ * 2)
  .toMat(TestSink.probe[Int])(Keep.both)
  .run()

sub.request(2)
pub.sendNext(1)
sub.expectNext(2)
pub.sendNext(2)
sub.expectNext(4)
pub.sendComplete()
sub.expectComplete()
```

### Kiểm tra materialized values

```scala
val result: Future[Int] = Source(1 to 10)
  .map(_ * 2)
  .runWith(Sink.fold[Int, Int](0)(_ + _))

Await.result(result, 3.seconds) shouldBe 110
```

### Kiểm tra error handling

```scala
val probe = Source(List(1, 0, 3))
  .map(10 / _)
  .runWith(TestSink.probe[Int])

probe.request(1)
probe.expectNext(10)
probe.request(1)
probe.expectError()   // ArithmeticException
```

---

## 15. Tổng hợp: Bảng so sánh nhanh

### Transformation

| Operator              | Input → Output | Mô tả                                  |
|-----------------------|----------------|-----------------------------------------|
| `map`                 | 1 → 1         | Biến đổi đơn giản                       |
| `mapConcat`           | 1 → N         | FlatMap (sync)                           |
| `statefulMapConcat`   | 1 → N         | FlatMap có state                         |
| `flatMapConcat`       | 1 → Stream    | FlatMap giữ thứ tự                      |
| `flatMapMerge`        | 1 → Stream    | FlatMap song song                        |
| `collect`             | 1 → 0/1       | map + filter (partial function)          |
| `scan`                | 1 → 1         | Fold emit mỗi bước                      |
| `fold`                | N → 1         | Gom thành 1 giá trị (emit cuối)         |
| `reduce`              | N → 1         | fold không cần init                      |
| `mapAsync`            | 1 → 1         | Async, giữ thứ tự                       |
| `mapAsyncUnordered`   | 1 → 1         | Async, không giữ thứ tự                 |

### Grouping

| Operator              | Mô tả                                         |
|-----------------------|------------------------------------------------|
| `grouped(n)`          | Gom n elements → Seq                           |
| `groupedWithin(n, d)` | Gom n elements hoặc d thời gian                |
| `groupedWeighted(w)`  | Gom theo trọng số                              |
| `groupBy(max, key)`   | Chia thành SubStreams                           |
| `sliding(n, step)`    | Sliding window                                 |
| `splitWhen(pred)`     | Tạo substream mới khi true (before)            |
| `splitAfter(pred)`    | Tạo substream mới khi true (after)             |

### Time-based

| Operator              | Mô tả                                         |
|-----------------------|------------------------------------------------|
| `throttle(n, d)`      | Rate limit n elements / d                      |
| `delay(d)`            | Trì hoãn mỗi element                          |
| `idleTimeout(d)`      | Fail nếu idle > d                              |
| `keepAlive(d, fn)`    | Inject element khi idle > d                    |
| `takeWithin(d)`       | Lấy elements trong d đầu tiên                  |
| `dropWithin(d)`       | Bỏ elements trong d đầu tiên                   |
| `completionTimeout(d)` | Fail nếu stream chưa done trong d             |
| `initialTimeout(d)`   | Fail nếu element đầu không đến trong d         |
| `backpressureTimeout(d)` | Fail nếu backpressure > d                   |

### Fan-out

| Stage                 | Hành vi                                        |
|-----------------------|------------------------------------------------|
| `Broadcast`           | Clone element đến N outputs                    |
| `Balance`             | Round-robin đến N outputs                      |
| `Partition`           | Route theo điều kiện đến N outputs             |
| `Unzip`               | Split tuple (A, B) thành 2 outputs             |
| `UnzipWith`           | Split/transform thành N outputs                |

### Fan-in

| Stage                 | Hành vi                                        |
|-----------------------|------------------------------------------------|
| `Merge`               | Gom N inputs (interleaved)                     |
| `MergePreferred`      | Gom N inputs, ưu tiên 1                        |
| `MergePrioritized`    | Gom N inputs, theo trọng số                    |
| `MergeLatest`         | Emit list latest values                        |
| `Concat`              | Nối tuần tự N inputs                           |
| `Zip`                 | Ghép cặp 2 inputs thành tuple                  |
| `ZipWith`             | Ghép cặp + transform                           |
| `ZipN`                | Ghép cặp N inputs                              |
| `ZipLatest`           | Ghép cặp latest values                         |

### Backpressure

| Operator              | Mô tả                                         |
|-----------------------|------------------------------------------------|
| `buffer(n, strategy)` | Đệm n elements                                 |
| `conflate(fn)`        | Gom khi consumer chậm                           |
| `extrapolate(fn)`     | Tạo thêm khi producer chậm                     |
| `expand(fn)`          | Luôn có sẵn element                             |
| `throttle(n, d)`      | Giới hạn throughput                             |

### Error Handling

| Operator/Pattern       | Mô tả                                         |
|-----------------------|------------------------------------------------|
| `recover`             | Catch error → 1 fallback element → complete     |
| `recoverWith`         | Catch error → fallback source                   |
| `recoverWithRetries`  | Retry N lần với fallback                        |
| `mapError`            | Transform error type                            |
| `divertTo`            | Route elements thỏa điều kiện sang sink khác    |
| `Supervision.Resume`  | Bỏ qua element lỗi                             |
| `Supervision.Restart` | Reset stage state                               |
| `Supervision.Stop`    | Dừng stream                                     |
| `RestartSource`       | Auto-restart source với backoff                 |

---

## Phụ lục: Import cheatsheet

```scala
// Core
import akka.stream._
import akka.stream.scaladsl._

// File IO
import akka.stream.scaladsl.{FileIO, Framing}
import akka.util.ByteString
import java.nio.file.Paths

// Graph DSL
import akka.stream.scaladsl.GraphDSL
import akka.stream.{ClosedShape, FlowShape, SourceShape, SinkShape}
import akka.stream.scaladsl.{Broadcast, Balance, Merge, Partition, Zip, Concat}

// Typed Actor-Stream integration
import akka.stream.typed.scaladsl.{ActorSource, ActorSink, ActorFlow}

// Supervision
import akka.stream.{ActorAttributes, Supervision}

// Restart
import akka.stream.RestartSettings
import akka.stream.scaladsl.{RestartSource, RestartSink, RestartFlow}

// Testing
import akka.stream.testkit.scaladsl.{TestSource, TestSink}

// Compression
import akka.stream.scaladsl.Compression

// Java IO interop
import akka.stream.scaladsl.StreamConverters
```

---

> **Ghi chú**: Tài liệu này cover Akka Streams 2.6.x (Classic + Typed APIs).
> Tham khảo thêm: https://doc.akka.io/docs/akka/current/stream/index.html

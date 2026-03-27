# SkyFlow — Akka Streams Learning Plan

Tài liệu này mô tả các tính năng Akka Streams được thiết kế để học từ cơ bản đến nâng cao,
tích hợp vào domain **Quản lý chuyến bay** của SkyFlow.

---

## Tổng quan kiến trúc

```
com.skyflow.streaming/
├── domain/
│   └── model/
│       ├── StreamingModels.scala        # Shared models cho streaming
│       └── StreamError.scala            # Error types cho stream processing
├── application/
│   └── service/
│       ├── FileProcessingService.scala  # Feature 1+2: CSV processing & file splitting
│       ├── BatchProcessingService.scala # Feature 3: Batch processing pipeline
│       ├── ErrorHandlingService.scala   # Feature 4: Error handling patterns
│       ├── BackpressureService.scala    # Feature 5: Backpressure & throttling
│       └── GraphDslService.scala        # Feature 6: Fan-out / Fan-in patterns
├── infrastructure/
│   └── stream/
│       ├── StreamSupervision.scala      # Custom supervision strategies
│       ├── StreamMetrics.scala          # Stream monitoring & logging
│       └── FileIOHelpers.scala          # File read/write utilities
└── interface/
    └── http/
        └── StreamingRoutes.scala        # REST API để trigger & monitor streams
```

---

## Thứ tự học

```
Feature 1 — CSV File Processing Pipeline     [Cơ bản]      ← bắt đầu tại đây
Feature 2 — File Splitting (Large → Small)   [Cơ bản]
Feature 3 — Batch Processing Pipeline        [Trung bình]
Feature 4 — Error Handling & Supervision      [Trung bình]  ← milestone error handling
Feature 5 — Backpressure & Throttling         [Trung bình]
Feature 6 — Graph DSL: Fan-out / Fan-in       [Nâng cao]    ← milestone graph DSL
Feature 7 — Actor ↔ Stream Integration        [Nâng cao]    ← kết nối với FlightActor
```

---

## Feature 1: CSV File Processing Pipeline

### Mục tiêu
Đọc file CSV lớn chứa danh sách booking requests, parse từng dòng thành domain object,
validate dữ liệu, và ghi kết quả ra file output.

### Akka Stream Concepts
- `Source` — nguồn dữ liệu (file)
- `Flow` — biến đổi dữ liệu (parse, validate, transform)
- `Sink` — đích đến (file output)
- `FileIO.fromPath` — đọc file dạng stream
- `Framing.delimiter` — chia ByteString thành dòng
- `map`, `filter` — operators cơ bản

### Input: `bookings_large.csv`
```csv
passengerId,passengerName,email,origin,destination,seatsRequested
P001,Nguyen Van A,nva@example.com,HAN,SGN,2
P002,Tran Thi B,ttb@example.com,HAN,DAD,1
P003,Le Van C,invalid-email,SGN,DAD,3
P004,Pham Thi D,ptd@example.com,HAN,SGN,0
```

### Pipeline

```
CSV File ──→ Read bytes ──→ Split lines ──→ Parse CSV ──→ Validate ──→ Write output
  Source       FileIO       Framing         Flow          Flow         Sink
```

### Diagram

```
┌─────────────┐     ┌─────────────┐      ┌─────────────┐      ┌──────────────┐
│  FileIO     │────▶│  Framingz   │────▶│  Parse CSV  │────▶│  Validate    │
│  .fromPath  │     │  .delimiter │      │  map(parse) │      │  filter/map  │
└─────────────┘     └─────────────┘      └─────────────┘      └──────┬───────┘
                                                                     │
                                                             ┌───────▼───────┐
                                                             │  FileIO       │
                                                             │  .toPath      │
                                                             │  (output.csv) │
                                                             └───────────────┘
```

### Implementation Sketch

```scala
object FileProcessingService {

  def processBookingsCsv(inputPath: Path, outputPath: Path)
                        (implicit system: ActorSystem[_]): Future[IOResult] = {

    val source: Source[ByteString, Future[IOResult]] = FileIO.fromPath(inputPath)

    val parseFlow: Flow[ByteString, BookingRecord, NotUsed] =
      Framing.delimiter(ByteString("\n"), maximumFrameLength = 1024, allowTruncation = true)
        .map(_.utf8String.trim)
        .drop(1) // bỏ header
        .map(parseCsvLine)

    val validateFlow: Flow[BookingRecord, ValidatedBooking, NotUsed] =
      Flow[BookingRecord]
        .map(validate)
        .collect { case Right(valid) => valid }

    val sink: Sink[ValidatedBooking, Future[IOResult]] =
      Flow[ValidatedBooking]
        .map(b => ByteString(b.toCsvLine + "\n"))
        .toMat(FileIO.toPath(outputPath))(Keep.right)

    source
      .via(parseFlow)
      .via(validateFlow)
      .runWith(sink)
  }
}
```

### Checklist học
- [ ] Hiểu `Source[Out, Mat]` — `Out` là element type, `Mat` là materialized value
- [ ] Hiểu `Framing.delimiter` — tại sao cần `maximumFrameLength`?
- [ ] Hiểu `Keep.right` vs `Keep.left` vs `Keep.both` — chọn materialized value nào?
- [ ] Thử thay đổi `maximumFrameLength` quá nhỏ → xem error gì xảy ra
- [ ] Chạy với file 10 dòng, 1000 dòng, 100K dòng → so sánh memory usage

---

## Feature 2: File Splitting (Large File → Smaller Chunks)

### Mục tiêu
Chia một file CSV lớn (ví dụ: 100K booking records) thành nhiều file nhỏ,
mỗi file chứa tối đa N dòng (ví dụ: 1000 dòng). Mô phỏng việc chia data
cho parallel processing.

### Akka Stream Concepts
- `grouped(n)` — gom N elements thành 1 Seq
- `zipWithIndex` — đánh số thứ tự
- `mapAsync` — xử lý bất đồng bộ (ghi file)
- `Sink.foreach` — side effect sink
- `statefulMapConcat` — stateful transformation (nâng cao)

### Pipeline

```
Large CSV ──→ Read lines ──→ Skip header ──→ Group(1000) ──→ Write chunk files
                                                │
                                            chunk_001.csv
                                            chunk_002.csv
                                            chunk_003.csv
                                            ...
```

### Diagram

```
┌─────────────┐     ┌─────────────┐     ┌──────────────┐     ┌────────────────┐
│  FileIO     │────▶│  Framing    │────▶│  grouped(N)  │────▶│  zipWithIndex  │
│  .fromPath  │     │  .delimiter │     │  Seq[String]  │     │  (Seq, idx)    │
└─────────────┘     └─────────────┘     └──────────────┘     └───────┬────────┘
                                                                     │
                                                             ┌───────▼────────┐
                                                             │  mapAsync(4)   │
                                                             │  writeChunk()  │
                                                             │  → chunk_N.csv │
                                                             └────────────────┘
```

### Implementation Sketch

```scala
object FileSplittingService {

  def splitFile(inputPath: Path, outputDir: Path, linesPerChunk: Int = 1000)
               (implicit system: ActorSystem[_]): Future[Done] = {

    // Đảm bảo output directory tồn tại
    Files.createDirectories(outputDir)

    // Lưu header để ghi vào mỗi chunk
    val headerLine: String = ... // đọc dòng đầu tiên

    FileIO.fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .drop(1)                          // bỏ header
      .grouped(linesPerChunk)           // gom thành từng batch
      .zipWithIndex                     // đánh số chunk: (Seq[String], Long)
      .mapAsync(4) { case (lines, idx) =>
        val chunkPath = outputDir.resolve(f"chunk_${idx + 1}%04d.csv")
        val content = (headerLine +: lines).mkString("\n")
        // Ghi file bất đồng bộ
        Source.single(ByteString(content))
          .runWith(FileIO.toPath(chunkPath))
      }
      .runWith(Sink.ignore)
  }
}
```

### Bài tập mở rộng
1. **Split by size**: Thay vì N dòng, split khi file chunk đạt X MB
2. **Dynamic naming**: Tên file dựa vào nội dung (VD: `HAN_SGN_001.csv` theo route)
3. **Merge lại**: Viết stream ngược lại — merge nhiều file nhỏ thành 1 file lớn

### Checklist học
- [ ] Hiểu `grouped(n)` — tạo `Seq[T]` từ stream `T`
- [ ] Hiểu `mapAsync(parallelism)` vs `mapAsyncUnordered` — khi nào dùng cái nào?
- [ ] Hiểu tại sao `mapAsync(4)` giúp ghi file nhanh hơn tuần tự
- [ ] Challenge: Nếu `linesPerChunk = 1` thì performance ra sao? Tại sao?

---

## Feature 3: Batch Processing Pipeline

### Mục tiêu
Đọc booking requests từ CSV, xử lý theo batch (nhóm theo route),
gửi từng batch đến `FlightActor` để allocate seats, và ghi kết quả.

### Akka Stream Concepts
- `groupBy` — chia stream thành SubFlows theo key
- `mergeSubstreams` — gom SubFlows lại
- `fold` / `reduce` — aggregation
- `mapAsync` + Actor `ask` pattern — tương tác với actors
- `scan` — running aggregation (tương tự fold nhưng emit mỗi bước)

### Pipeline

```
CSV File ──→ Parse ──→ GroupBy(route) ──→ Batch(50) ──→ Ask FlightActor ──→ Results
                            │
                    ┌───────┼───────┐
                    ▼       ▼       ▼
                 HAN→SGN  HAN→DAD  SGN→DAD
                    │       │       │
                 batch50  batch50  batch50
                    │       │       │
                    └───────┼───────┘
                            ▼
                      mergeSubstreams
                            │
                      Write results
```

### Implementation Sketch

```scala
object BatchProcessingService {

  def processByRoute(inputPath: Path, flightRegistry: ActorRef[RegistryCommand])
                    (implicit system: ActorSystem[_]): Future[BatchReport] = {

    implicit val timeout: Timeout = 5.seconds

    FileIO.fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .drop(1)
      .map(parseCsvLine)
      .collect { case Right(booking) => booking }
      // Nhóm theo route (origin → destination)
      .groupBy(maxSubstreams = 100, _.routeKey)
      // Trong mỗi substream: gom thành batch 50
      .grouped(50)
      // Gửi batch đến actor
      .mapAsync(1) { batch =>
        allocateBatch(batch, flightRegistry)
      }
      .mergeSubstreams
      // Tổng hợp kết quả
      .runWith(Sink.fold(BatchReport.empty)(_.merge(_)))
  }
}
```

### Checklist học
- [ ] Hiểu `groupBy(maxSubstreams, keyFn)` — tại sao cần `maxSubstreams`?
- [ ] Hiểu rủi ro khi `maxSubstreams` quá nhỏ so với số key thực tế
- [ ] Hiểu `mergeSubstreams` vs `mergeSubstreamsWithParallelism`
- [ ] Hiểu sự khác biệt: `fold` (emit cuối) vs `scan` (emit mỗi bước) vs `reduce`
- [ ] Thử bỏ `mergeSubstreams` → compiler báo gì?

---

## Feature 4: Error Handling & Supervision

### Mục tiêu
Xử lý các loại lỗi trong stream processing:
- **Parse errors** — CSV dòng bị lỗi format
- **Validation errors** — dữ liệu không hợp lệ
- **Transient errors** — lỗi tạm thời (DB timeout, network)
- **Fatal errors** — lỗi không recover được

### Akka Stream Concepts

#### 4.1 — Supervision Strategy (stream-level)
```scala
// 3 chiến lược:
// - Resume:  bỏ qua element lỗi, tiếp tục stream
// - Restart: reset stage state, tiếp tục stream
// - Stop:    dừng stream (default)

val decider: Supervision.Decider = {
  case _: ParseException    => Supervision.Resume   // bỏ qua dòng lỗi
  case _: ValidationError   => Supervision.Resume   // bỏ qua dữ liệu invalid
  case _: TimeoutException  => Supervision.Restart   // retry (reset state)
  case _                    => Supervision.Stop       // dừng stream
}

source
  .via(riskyFlow)
  .withAttributes(ActorAttributes.supervisionStrategy(decider))
  .runWith(sink)
```

#### 4.2 — Element-level error handling (Either pattern)
```scala
// Thay vì throw exception, wrap kết quả trong Either
val safeParseFlow: Flow[String, Either[ParseError, BookingRecord], NotUsed] =
  Flow[String].map { line =>
    Try(parseCsvLine(line)) match {
      case Success(record) => Right(record)
      case Failure(ex)     => Left(ParseError(line, ex.getMessage))
    }
  }
```

#### 4.3 — Dead Letter Queue (DLQ) pattern
```scala
// Chia stream thành 2 nhánh: success và error
// Error records được ghi vào file riêng để review sau

val partition = Partition[Either[ParseError, BookingRecord]](2, {
  case Left(_)  => 0  // error → output 0
  case Right(_) => 1  // success → output 1
})
```

#### 4.4 — Retry pattern với `RestartSource`
```scala
// Tự động restart stream khi gặp lỗi transient
val restartableSource = RestartSource.withBackoff(
  RestartSettings(
    minBackoff = 1.second,
    maxBackoff = 30.seconds,
    randomFactor = 0.2
  ).withMaxRestarts(5, within = 1.minute)
) { () =>
  sourceWhichMayFail()
}
```

### Pipeline: Robust CSV Processing

```
               ┌──────────────────────────────────────────────────────────┐
               │                   Supervision Strategy                   │
               │                  (Resume on ParseError)                  │
               │                                                          │
CSV File ──→ Parse ──→ Either[Error, Record] ──→ Partition               │
               │                                    │         │           │
               │                              Error branch  Success branch│
               │                                    │         │           │
               │                              DLQ file    Process &       │
               │                              (errors.csv)  Output        │
               └──────────────────────────────────────────────────────────┘
```

### Implementation Sketch

```scala
object ErrorHandlingService {

  /** Pipeline xử lý CSV với đầy đủ error handling */
  def processWithErrorHandling(
    inputPath: Path,
    outputPath: Path,
    errorPath: Path
  )(implicit system: ActorSystem[_]): Future[ProcessingReport] = {

    val source = FileIO.fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .drop(1)

    // Parse an toàn — không throw exception
    val safeParse: Flow[String, Either[ProcessingError, BookingRecord], NotUsed] =
      Flow[String].map(safeParseLine)

    // Chia thành 2 nhánh
    val errorSink = Flow[ProcessingError]
      .map(e => ByteString(s"${e.line},${e.reason}\n"))
      .toMat(FileIO.toPath(errorPath))(Keep.right)

    val successSink = Flow[BookingRecord]
      .map(r => ByteString(r.toCsvLine + "\n"))
      .toMat(FileIO.toPath(outputPath))(Keep.right)

    // Graph DSL để split stream
    RunnableGraph.fromGraph(GraphDSL.createWithMaterializer() { implicit builder => mat =>
      import GraphDSL.Implicits._

      val parse = builder.add(safeParse)
      val partition = builder.add(Partition[Either[ProcessingError, BookingRecord]](2, {
        case Left(_)  => 0
        case Right(_) => 1
      }))

      val extractError = builder.add(Flow[Either[ProcessingError, BookingRecord]].collect {
        case Left(err) => err
      })
      val extractSuccess = builder.add(Flow[Either[ProcessingError, BookingRecord]].collect {
        case Right(record) => record
      })

      source ~> parse ~> partition
                         partition.out(0) ~> extractError  ~> errorSink
                         partition.out(1) ~> extractSuccess ~> successSink

      ClosedShape
    }).run()
  }
}
```

### Bài tập
1. **Thử bỏ supervision strategy** → stream dừng khi gặp dòng CSV lỗi
2. **Đổi Resume → Restart** → quan sát sự khác biệt (state bị reset)
3. **Thêm retry cho DB write** → dùng `RetryFlow` hoặc `mapAsync` + retry logic
4. **Đếm errors** → dùng `Sink.fold` để đếm số lỗi và báo cáo cuối stream

### Checklist học
- [ ] Phân biệt 3 supervision strategies: Resume / Restart / Stop
- [ ] Hiểu tại sao `Either` pattern tốt hơn throw exception trong stream
- [ ] Hiểu `Partition` — chia 1 stream thành N outlets
- [ ] Hiểu `RestartSource.withBackoff` — exponential backoff retry
- [ ] Hiểu `recover` vs `recoverWith` — fallback khi stream fail

---

## Feature 5: Backpressure & Throttling

### Mục tiêu
Hiểu cơ chế backpressure — điểm mạnh nhất của Akka Streams (Reactive Streams spec).
Mô phỏng: producer nhanh (đọc CSV rất nhanh) + consumer chậm (ghi DB chậm).

### Akka Stream Concepts
- `throttle` — giới hạn throughput
- `buffer` — đệm khi producer nhanh hơn consumer
- `OverflowStrategy` — hành vi khi buffer đầy (dropHead, dropTail, backpressure, fail)
- `conflate` — gom khi consumer chậm (keep latest summary)
- `extrapolate` — tạo thêm khi producer chậm

### 5.1 — Throttle: Rate Limiting

```scala
// Giới hạn 100 records/giây
source
  .throttle(elements = 100, per = 1.second)
  .mapAsync(4)(processRecord)
  .runWith(Sink.ignore)
```

### 5.2 — Buffer + Overflow Strategy

```scala
// Buffer 1000 elements, khi đầy → drop phần tử cũ nhất
source
  .buffer(size = 1000, overflowStrategy = OverflowStrategy.dropHead)
  .mapAsync(1)(slowConsumer)
  .runWith(Sink.ignore)

// Các chiến lược:
// OverflowStrategy.backpressure  ← default, an toàn nhất
// OverflowStrategy.dropHead      ← bỏ cũ nhất (latest wins)
// OverflowStrategy.dropTail      ← bỏ mới nhất (oldest wins)
// OverflowStrategy.dropBuffer    ← xóa toàn bộ buffer
// OverflowStrategy.dropNew       ← bỏ element incoming
// OverflowStrategy.fail          ← fail stream khi buffer đầy
```

### 5.3 — Conflate: Tổng hợp khi consumer chậm

```scala
// Khi consumer chậm, gom N booking requests thành 1 summary
source
  .conflate { (summary, newRecord) =>
    summary.copy(
      totalRecords = summary.totalRecords + 1,
      totalSeats = summary.totalSeats + newRecord.seatsRequested
    )
  }
  .mapAsync(1)(processSummary)
  .runWith(Sink.ignore)
```

### Demo Pipeline: Fast Producer + Slow Consumer

```
                    ┌─backpressure signal──┐
                    │                      │
                    ▼                      │
Fast CSV Reader ──→ buffer(1000) ──→ throttle(100/s) ──→ Slow DB Write
   ~10K/s                                                    ~50/s
```

### Implementation Sketch

```scala
object BackpressureService {

  /** Demo backpressure: fast source + slow sink */
  def demonstrateBackpressure(inputPath: Path)
                             (implicit system: ActorSystem[_]): Future[BackpressureReport] = {

    val fastSource = FileIO.fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .drop(1)

    val slowProcessing: Flow[String, ProcessedRecord, NotUsed] =
      Flow[String]
        .mapAsync(4) { line =>
          // Mô phỏng xử lý chậm (DB write, API call, ...)
          akka.pattern.after(10.millis)(Future.successful(processLine(line)))
        }

    // Variant 1: Không throttle — backpressure tự nhiên
    val naturalBackpressure = fastSource
      .via(slowProcessing)
      .runWith(Sink.fold(0)((count, _) => count + 1))

    // Variant 2: Có throttle — rate limiting rõ ràng
    val throttled = fastSource
      .throttle(100, 1.second) // max 100 elements/giây
      .via(slowProcessing)
      .runWith(Sink.fold(0)((count, _) => count + 1))

    // Variant 3: Buffer + overflow
    val buffered = fastSource
      .buffer(500, OverflowStrategy.dropHead)
      .via(slowProcessing)
      .runWith(Sink.fold(0)((count, _) => count + 1))
  }
}
```

### Bài tập
1. **Đo throughput**: Thêm timestamp → tính records/second
2. **So sánh strategies**: Chạy cùng input với `backpressure`, `dropHead`, `fail`
3. **Visualize**: In log mỗi 100 records để thấy tốc độ xử lý
4. **Conflate demo**: Thay vì drop, dùng `conflate` để merge records

### Checklist học
- [ ] Hiểu backpressure hoạt động thế nào (demand signal upstream)
- [ ] Phân biệt 6 `OverflowStrategy` — khi nào dùng cái nào?
- [ ] Hiểu `throttle(elements, per, maximumBurst)`
- [ ] Hiểu `conflate` vs `buffer` — trường hợp sử dụng khác nhau
- [ ] Hiểu tại sao Akka Streams an toàn hơn manual queue + thread pool

---

## Feature 6: Graph DSL — Fan-out / Fan-in

### Mục tiêu
Xây dựng pipeline phức tạp với nhiều input/output, phân chia và gom dữ liệu.

### Akka Stream Concepts
- `GraphDSL` — xây dựng graph dạng visual
- `Broadcast[T]` — 1 input → N outputs (clone mọi element)
- `Merge[T]` — N inputs → 1 output
- `Balance[T]` — 1 input → N outputs (round-robin, load balancing)
- `Zip[A, B]` — 2 inputs → 1 output (tuple)
- `Partition[T]` — 1 input → N outputs (theo điều kiện)
- `MergePreferred[T]` — merge với priority

### 6.1 — Broadcast: Gửi data đến nhiều đích

```
                        ┌──→ Console Log (realtime monitoring)
                        │
CSV Source ──→ Parse ──→ Broadcast ──→ File Output (processed.csv)
                        │
                        └──→ Metrics Counter (statistics)
```

### 6.2 — Balance: Parallel Workers

```
                        ┌──→ Worker 1 (mapAsync) ──┐
                        │                           │
CSV Source ──→ Balance ──→ Worker 2 (mapAsync) ──→ Merge ──→ Output
                        │                           │
                        └──→ Worker 3 (mapAsync) ──┘
```

### 6.3 — Complex Graph: Multi-route Processing

```
                                    ┌──→ HAN-SGN Processor ──┐
                                    │                         │
CSV Source ──→ Parse ──→ Partition ──→ HAN-DAD Processor ──→ Merge ──→ Report
                                    │                         │
                                    └──→ SGN-DAD Processor ──┘
                                    │
                                    └──→ Unknown Route ──→ DLQ
```

### Implementation Sketch

```scala
object GraphDslService {

  /** Broadcast: ghi output + log + metrics cùng lúc */
  def broadcastPipeline(inputPath: Path, outputPath: Path)
                       (implicit system: ActorSystem[_]): Future[ProcessingReport] = {

    RunnableGraph.fromGraph(GraphDSL.createWithMaterializer() { implicit builder => mat =>
      import GraphDSL.Implicits._

      // Source
      val source = builder.add(
        FileIO.fromPath(inputPath)
          .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
          .map(_.utf8String.trim)
          .drop(1)
          .map(parseLine)
      )

      // Fan-out
      val broadcast = builder.add(Broadcast[BookingRecord](3))

      // 3 Sinks
      val fileSink = builder.add(
        Flow[BookingRecord]
          .map(r => ByteString(r.toCsvLine + "\n"))
          .to(FileIO.toPath(outputPath))
      )

      val logSink = builder.add(
        Sink.foreach[BookingRecord](r =>
          system.log.info(s"Processing: ${r.passengerId} on ${r.routeKey}")
        )
      )

      val countSink = builder.add(
        Sink.fold[Int, BookingRecord](0)((count, _) => count + 1)
      )

      // Wiring
      source ~> broadcast
                broadcast ~> fileSink
                broadcast ~> logSink
                broadcast ~> countSink

      ClosedShape
    }).run()
  }

  /** Balance: parallel processing với N workers */
  def balancedPipeline(inputPath: Path, workerCount: Int = 4)
                      (implicit system: ActorSystem[_]): Future[Done] = {

    RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val source = builder.add(fileSource(inputPath))
      val balance = builder.add(Balance[BookingRecord](workerCount))
      val merge = builder.add(Merge[ProcessedRecord](workerCount))
      val sink = builder.add(Sink.foreach[ProcessedRecord](r =>
        println(s"Processed: ${r.id} by worker")
      ))

      source ~> balance
      for (i <- 0 until workerCount) {
        balance ~> worker(i) ~> merge
      }
      merge ~> sink

      ClosedShape
    }).run()
  }
}
```

### Bài tập
1. **Broadcast + collect**: 3 sinks thu thập khác nhau (count, sum, max)
2. **Balance benchmark**: So sánh 1 worker vs 4 workers vs 8 workers
3. **Zip**: Kết hợp 2 CSV files (bookings + passengers) dựa trên passengerId
4. **Custom graph shape**: Tạo reusable Flow có 2 outputs (success + error)

### Checklist học
- [ ] Hiểu `GraphDSL.create()` — builder pattern cho graph
- [ ] Hiểu operator `~>` — connect nối các stage
- [ ] Phân biệt `Broadcast` (clone) vs `Balance` (round-robin) vs `Partition` (condition)
- [ ] Hiểu `ClosedShape` vs `FlowShape` vs custom `Shape`
- [ ] Hiểu tại sao tất cả outlets phải được connect (compile-time check)

---

## Feature 7: Actor ↔ Stream Integration

### Mục tiêu
Kết nối Akka Streams với hệ thống actor hiện tại của SkyFlow.
Stream đọc booking CSV → gửi `AllocateSeats` command đến `FlightActor` → thu thập kết quả.

### Akka Stream Concepts
- `ActorSource` — tạo Source từ actor messages
- `ActorSink` — gửi stream elements đến actor
- `mapAsync` + `ask` — pattern phổ biến nhất
- `Source.queue` — push-based source (manual emit)
- `Sink.actorRef` — forward elements đến actor
- `Flow.ask` — built-in ask flow

### 7.1 — mapAsync + Ask Pattern

```scala
// Stream gửi command đến FlightActor, nhận response
val allocationFlow: Flow[BookingRecord, AllocationResult, NotUsed] =
  Flow[BookingRecord]
    .mapAsync(parallelism = 4) { booking =>
      implicit val timeout: Timeout = 5.seconds
      flightActor.ask[FlightResponse] { replyTo =>
        AllocateSeats(booking.flightId, booking.seatsRequested, replyTo)
      }.map(response => AllocationResult(booking, response))
    }
```

### 7.2 — ActorSource: Flight Status Event Stream

```scala
// Actor push events vào stream → SSE/WebSocket cho frontend
val flightStatusSource: Source[FlightStatusUpdate, ActorRef[FlightStatusUpdate]] =
  ActorSource.actorRef[FlightStatusUpdate](
    completionMatcher = { case FlightStatusUpdate(_, "COMPLETED") => },
    failureMatcher = PartialFunction.empty,
    bufferSize = 256,
    overflowStrategy = OverflowStrategy.dropHead
  )
```

### 7.3 — Source.queue: Manual Push

```scala
// Tạo SourceQueue để push elements từ bên ngoài stream
val (queue, done) = Source.queue[BookingRecord](bufferSize = 1000)
  .via(processFlow)
  .toMat(Sink.ignore)(Keep.both)
  .run()

// Push từ HTTP request handler hoặc actor
queue.offer(newBooking) // returns Future[QueueOfferResult]
```

### Full Pipeline: CSV → Actor → Report

```
┌─────────┐     ┌─────────┐     ┌──────────────┐     ┌──────────────┐
│  CSV     │────▶│  Parse  │────▶│  mapAsync    │────▶│  Aggregate   │
│  Source  │     │  Flow   │     │  ask(Actor)  │     │  Results     │
└─────────┘     └─────────┘     └──────┬───────┘     └──────┬───────┘
                                       │                     │
                                ┌──────▼───────┐     ┌──────▼───────┐
                                │ FlightActor  │     │  Report      │
                                │ (Event       │     │  Sink        │
                                │  Sourced)    │     │  (File/Log)  │
                                └──────────────┘     └──────────────┘
```

### Implementation Sketch

```scala
object ActorStreamIntegrationService {

  def processBookingsViaActors(
    inputPath: Path,
    flightRegistry: ActorRef[RegistryCommand],
    outputPath: Path
  )(implicit system: ActorSystem[_]): Future[IntegrationReport] = {

    implicit val timeout: Timeout = 5.seconds

    val source = FileIO.fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .drop(1)
      .map(parseBookingLine)
      .collect { case Right(booking) => booking }

    // Ask FlightActor để allocate seats
    val actorFlow: Flow[BookingRecord, AllocationResult, NotUsed] =
      Flow[BookingRecord]
        .groupBy(100, _.routeKey)
        .grouped(20) // batch 20 bookings per route
        .mapAsync(1) { batch =>
          allocateBatchViaActor(batch, flightRegistry)
        }
        .mergeSubstreams

    // Ghi kết quả
    val reportSink: Sink[AllocationResult, Future[IntegrationReport]] =
      Sink.fold(IntegrationReport.empty) { (report, result) =>
        report.add(result)
      }

    source
      .via(actorFlow)
      .toMat(reportSink)(Keep.right)
      .run()
  }
}
```

### Checklist học
- [ ] Hiểu `mapAsync(parallelism)` + `ask` — pattern chính cho Actor ↔ Stream
- [ ] Hiểu `ActorSource.actorRef` — tạo Source từ actor (push model)
- [ ] Hiểu `Source.queue` — khi nào dùng thay cho ActorSource?
- [ ] Hiểu backpressure giữa Stream và Actor — actor không có backpressure tự nhiên
- [ ] Hiểu `Sink.actorRefWithBackpressure` — actor trả về Ack để kiểm soát flow

---

## Sample Data Files

Cần tạo trong `src/main/resources/streaming-samples/`:

| File | Mô tả | Kích thước |
|------|--------|-----------|
| `bookings_small.csv` | 10 records, để test cơ bản | ~500B |
| `bookings_medium.csv` | 1,000 records | ~50KB |
| `bookings_large.csv` | 100,000 records (generated) | ~5MB |
| `bookings_malformed.csv` | Chứa dòng lỗi, để test error handling | ~1KB |

### Generator Script

```scala
// Có thể viết 1 Scala script hoặc main method để generate CSV lớn
object BookingDataGenerator {
  def generateCsv(path: Path, recordCount: Int): Unit = {
    val routes = List(("HAN","SGN"), ("HAN","DAD"), ("SGN","DAD"),
                      ("SGN","PQC"), ("HAN","CXR"), ("DAD","PQC"))
    val random = new Random(42) // fixed seed cho reproducibility

    val writer = Files.newBufferedWriter(path)
    writer.write("passengerId,passengerName,email,origin,destination,seatsRequested\n")

    (1 to recordCount).foreach { i =>
      val (origin, dest) = routes(random.nextInt(routes.length))
      val seats = random.nextInt(5) + 1
      writer.write(f"P$i%06d,Passenger $i,p$i@example.com,$origin,$dest,$seats\n")
    }
    writer.close()
  }
}
```

---

## REST API cho Stream Features

### Endpoints

```
POST   /api/streaming/csv/process          Trigger CSV processing pipeline (Feature 1)
POST   /api/streaming/csv/split            Trigger file splitting (Feature 2)
POST   /api/streaming/batch/process        Trigger batch processing (Feature 3)
POST   /api/streaming/demo/backpressure    Run backpressure demo (Feature 5)
GET    /api/streaming/status               Get stream processing status
GET    /api/streaming/report/{runId}       Get processing report
```

### Example Request — Process CSV

```bash
curl -X POST http://localhost:8080/api/streaming/csv/process \
  -H "Content-Type: application/json" \
  -d '{
    "inputFile": "bookings_small.csv",
    "outputFile": "processed_output.csv"
  }'
```

### Example Response

```json
{
  "runId": "stream-run-abc123",
  "status": "completed",
  "report": {
    "totalRecords": 10,
    "processedRecords": 8,
    "errorRecords": 2,
    "durationMs": 1234,
    "throughput": "8.1 records/sec"
  }
}
```

---

## Tổng kết Akka Stream Concepts theo Feature

| Concept | Feature | Mức độ |
|---------|---------|--------|
| Source, Flow, Sink | 1 | ⭐ |
| FileIO, Framing | 1, 2 | ⭐ |
| map, filter, collect | 1 | ⭐ |
| grouped, zipWithIndex | 2, 3 | ⭐⭐ |
| mapAsync, mapAsyncUnordered | 2, 3, 7 | ⭐⭐ |
| groupBy, mergeSubstreams | 3 | ⭐⭐ |
| fold, scan, reduce | 3, 5 | ⭐⭐ |
| Supervision Strategy | 4 | ⭐⭐ |
| Either pattern (error) | 4 | ⭐⭐ |
| RestartSource.withBackoff | 4 | ⭐⭐ |
| Partition | 4, 6 | ⭐⭐ |
| throttle | 5 | ⭐⭐ |
| buffer + OverflowStrategy | 5 | ⭐⭐ |
| conflate, extrapolate | 5 | ⭐⭐⭐ |
| GraphDSL | 6 | ⭐⭐⭐ |
| Broadcast, Balance, Merge | 6 | ⭐⭐⭐ |
| Zip, MergePreferred | 6 | ⭐⭐⭐ |
| ActorSource, ActorSink | 7 | ⭐⭐⭐ |
| Source.queue | 7 | ⭐⭐⭐ |
| ask pattern in stream | 7 | ⭐⭐⭐ |
| Keep.left/right/both | All | ⭐ |
| Materialized values | All | ⭐⭐ |

---

## Tips khi học

1. **Chạy từng feature riêng lẻ** — mỗi service có thể test độc lập qua REST API
2. **Đọc log cẩn thận** — Akka Streams có log rất informative ở DEBUG level
3. **Dùng `akka-stream-testkit`** — TestSource, TestSink cho unit test
4. **Visualize pipeline** — vẽ diagram trước khi code
5. **Materialized value là gì?** — luôn nghĩ `Source[Out, Mat]` có 2 type params

---

*Tài liệu bổ sung — Akka Streams Documentation: https://doc.akka.io/docs/akka/current/stream/index.html*

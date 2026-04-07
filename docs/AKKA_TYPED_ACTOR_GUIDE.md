# Hướng dẫn học Akka Actor Typed

> Project: skyFlow | Scala 2.13.12 | Akka 2.6.21

---

## Mục lục

1. [Actor Model là gì?](#1-actor-model-là-gì)
2. [Classic Actor vs Typed Actor](#2-classic-actor-vs-typed-actor)
3. [Kiến trúc cơ bản của Typed Actor](#3-kiến-trúc-cơ-bản-của-typed-actor)
4. [Behavior — Trái tim của Typed Actor](#4-behavior--trái-tim-của-typed-actor)
5. [Tạo Actor đầu tiên](#5-tạo-actor-đầu-tiên)
6. [ActorRef và gửi message](#6-actorref-và-gửi-message)
7. [Request-Response Pattern (Ask Pattern)](#7-request-response-pattern-ask-pattern)
8. [Actor Lifecycle](#8-actor-lifecycle)
9. [Actor Hierarchy & Supervision](#9-actor-hierarchy--supervision)
10. [Stash — Trì hoãn xử lý message](#10-stash--trì-hoãn-xử-lý-message)
11. [Timers & Scheduling](#11-timers--scheduling)
12. [Interaction Patterns nâng cao](#12-interaction-patterns-nâng-cao)
13. [Testing Typed Actor](#13-testing-typed-actor)
14. [EventSourced Actor (Persistence)](#14-eventsourced-actor-persistence)
15. [Best Practices](#15-best-practices)
16. [Bài tập thực hành theo project skyFlow](#16-bài-tập-thực-hành-theo-project-skyflow)

---

## 1. Actor Model là gì?

Actor Model là mô hình lập trình concurrent dựa trên ý tưởng:

- **Mọi thứ đều là Actor** — đơn vị tính toán nhỏ nhất
- **Actor giao tiếp bằng message** — không chia sẻ state (no shared mutable state)
- **Actor xử lý message tuần tự** — mỗi lần chỉ xử lý 1 message (thread-safe by design)

```
        ┌──────────┐   message   ┌──────────┐
        │ Actor A  │ ──────────► │ Actor B  │
        └──────────┘             └──────────┘
              │                        │
              │ spawn                  │ message
              ▼                        ▼
        ┌──────────┐             ┌──────────┐
        │ Actor C  │             │ Actor D  │
        └──────────┘             └──────────┘
```

**3 khả năng cơ bản khi nhận message:**
1. Gửi message cho actor khác
2. Tạo actor con (child actors)
3. Thay đổi behavior cho message tiếp theo

---

## 2. Classic Actor vs Typed Actor

| Đặc điểm | Classic (`akka.actor`) | Typed (`akka.actor.typed`) |
|-----------|----------------------|---------------------------|
| Message type | `Any` — không type-safe | `T` — generic, compiler check |
| API | `Actor` trait, override `receive` | `Behavior[T]` functional |
| ActorRef | `ActorRef` (untyped) | `ActorRef[T]` (typed) |
| Context | `ActorContext` | `ActorContext[T]` |
| Sender | `sender()` implicit | Phải truyền `replyTo: ActorRef[Response]` |
| Supervision | Override `supervisorStrategy` | `Behaviors.supervise(...)` |
| Recommend | Legacy | **Mặc định cho project mới** ✓ |

**Typed Actor buộc bạn nghĩ rõ ràng hơn** — compiler giúp bắt lỗi sai message type lúc compile.

---

## 3. Kiến trúc cơ bản của Typed Actor

```scala
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
```

### Cấu trúc 1 actor điển hình:

```
┌─────────────────────────────────────────────┐
│  object MyActor                             │
│                                             │
│  1. sealed trait Command  ← Protocol        │
│     case class DoThis(data: String,         │
│       replyTo: ActorRef[Response]) extends   │
│       Command                               │
│                                             │
│  2. sealed trait Response ← Responses       │
│     case class Done(result: String) extends │
│       Response                              │
│                                             │
│  3. def apply(): Behavior[Command]          │
│     ← Entry point (factory method)          │
│                                             │
│  4. private def running(state: State):      │
│     Behavior[Command]                       │
│     ← Behavior functions (state machine)    │
│                                             │
└─────────────────────────────────────────────┘
```

---

## 4. Behavior — Trái tim của Typed Actor

`Behavior[T]` mô tả cách actor phản ứng với message kiểu `T`.

### Các Behavior factory phổ biến:

```scala
// 1. Behaviors.receive — Có access context
Behaviors.receive[Command] { (context, message) =>
  message match {
    case SayHello(name) =>
      context.log.info(s"Hello $name!")
      Behaviors.same  // giữ nguyên behavior
  }
}

// 2. Behaviors.receiveMessage — Chỉ cần message, không cần context
Behaviors.receiveMessage[Command] {
  case SayHello(name) =>
    println(s"Hello $name!")
    Behaviors.same
}

// 3. Behaviors.setup — Khởi tạo 1 lần (init resources, spawn children)
Behaviors.setup[Command] { context =>
  val child = context.spawn(ChildActor(), "child")
  // Return behavior sau khi setup xong
  Behaviors.receiveMessage {
    case msg => 
      child ! Forward(msg)
      Behaviors.same
  }
}

// 4. Behaviors.empty — Không xử lý message nào
Behaviors.empty[Command]

// 5. Behaviors.stopped — Dừng actor
Behaviors.stopped

// 6. Behaviors.same — Giữ nguyên behavior hiện tại
Behaviors.same
```

### State Machine bằng cách đổi Behavior:

```scala
object Switch {
  sealed trait Command
  case object Toggle extends Command
  case class GetState(replyTo: ActorRef[Boolean]) extends Command

  def apply(): Behavior[Command] = off()  // Start ở trạng thái OFF

  private def off(): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case Toggle =>
        context.log.info("Switching ON")
        on()  // ← Chuyển sang behavior on()
      case GetState(replyTo) =>
        replyTo ! false
        Behaviors.same
    }
  }

  private def on(): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case Toggle =>
        context.log.info("Switching OFF")
        off()  // ← Chuyển sang behavior off()
      case GetState(replyTo) =>
        replyTo ! true
        Behaviors.same
    }
  }
}
```

---

## 5. Tạo Actor đầu tiên

### Ví dụ: Greeter Actor

```scala
object Greeter {
  // 1. Định nghĩa Protocol
  sealed trait Command
  final case class Greet(name: String, replyTo: ActorRef[Greeted]) extends Command

  final case class Greeted(name: String)

  // 2. Factory method trả về Behavior
  def apply(): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case Greet(name, replyTo) =>
        context.log.info("Hello {}!", name)
        replyTo ! Greeted(name)
        Behaviors.same
    }
  }
}
```

### Chạy với ActorSystem:

```scala
object Main extends App {
  // ActorSystem là root actor — guardian actor
  val system: ActorSystem[Greeter.Command] =
    ActorSystem(Greeter(), "greeter-system")

  // Gửi message
  // (ở đây đơn giản dùng fire-and-forget qua system.ignoreRef)
  system ! Greeter.Greet("Akka", system.ignoreRef)

  // Terminate sau 3 giây
  Thread.sleep(3000)
  system.terminate()
}
```

### Guardian Actor pattern (recommended):

```scala
object Guardian {
  sealed trait Command
  case object Start extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    // Spawn các child actors
    val greeter = context.spawn(Greeter(), "greeter")

    Behaviors.receiveMessage {
      case Start =>
        greeter ! Greeter.Greet("World", context.system.ignoreRef)
        Behaviors.same
    }
  }
}

// Main
val system = ActorSystem(Guardian(), "skyflow-system")
system ! Guardian.Start
```

---

## 6. ActorRef và gửi message

### Fire-and-Forget (Tell pattern):

```scala
// Dùng toán tử !
val greeter: ActorRef[Greeter.Command] = ???
greeter ! Greeter.Greet("Alice", replyTo)
```

**Type-safe**: `greeter ! 42` → COMPILE ERROR vì `42` không phải `Greeter.Command`

### Tại sao cần `replyTo`?

Trong typed actor **không có `sender()`**. Phải truyền rõ ràng:

```scala
case class GetFlights(replyTo: ActorRef[List[Flight]]) extends Command

// Khi gửi:
flightActor ! GetFlights(myActorRef)
```

---

## 7. Request-Response Pattern (Ask Pattern)

Khi cần đợi response (trả về `Future`):

```scala
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import scala.concurrent.duration._

implicit val timeout: Timeout = 3.seconds
implicit val scheduler = system.scheduler

// Ask trả về Future[Response]
val futureResponse: Future[Greeter.Greeted] =
  greeter.ask(ref => Greeter.Greet("Bob", ref))

futureResponse.onComplete {
  case Success(Greeter.Greeted(name)) => println(s"$name was greeted")
  case Failure(ex) => println(s"Failed: ${ex.getMessage}")
}
```

### Ask từ bên trong Actor khác (actor-to-actor):

```scala
object Manager {
  sealed trait Command
  case class RequestGreeting(name: String) extends Command
  // Wrapper để adapt response về Command type
  private case class WrappedGreeted(greeted: Greeter.Greeted) extends Command

  def apply(greeter: ActorRef[Greeter.Command]): Behavior[Command] =
    Behaviors.setup { context =>
      // Tạo adapter: ActorRef[Greeted] → ActorRef[Command]
      val greetedAdapter: ActorRef[Greeter.Greeted] =
        context.messageAdapter(greeted => WrappedGreeted(greeted))

      Behaviors.receiveMessage {
        case RequestGreeting(name) =>
          greeter ! Greeter.Greet(name, greetedAdapter)
          Behaviors.same
        case WrappedGreeted(Greeter.Greeted(name)) =>
          context.log.info(s"Manager received: $name was greeted")
          Behaviors.same
      }
    }
}
```

> **Quan trọng**: `context.messageAdapter` là cách chính để nhận response từ actor khác type. Nó wrap response vào message type của actor hiện tại.

### So sánh các cách giao tiếp:

| Pattern | Khi nào dùng | Blocking? |
|---------|-------------|-----------|
| Tell (`!`) | Fire-and-forget, không cần response | Không |
| Ask (`?`) | Cần Future[Response] từ ngoài actor | Không (async Future) |
| `messageAdapter` | Actor A cần response từ Actor B (khác type) | Không |
| `replyTo` trực tiếp | Response cùng type hoặc đã biết ref | Không |

---

## 8. Actor Lifecycle

```
                  spawn
                    │
                    ▼
            ┌──────────────┐
            │  PreRestart   │ ← (supervision)
            └──────┬───────┘
                   │
                   ▼
            ┌──────────────┐
   ───────► │   Running     │ ◄──── receive messages
            └──────┬───────┘
                   │
          stop / Behaviors.stopped
                   │
                   ▼
            ┌──────────────┐
            │  PostStop     │ ← Cleanup resources
            └──────────────┘
```

### Signal handling:

```scala
Behaviors.receive[Command] { (context, message) =>
  message match {
    case DoWork(data) =>
      // handle message
      Behaviors.same
  }
}.receiveSignal {
  case (context, PreRestart) =>
    context.log.info("Actor restarting...")
    Behaviors.same
  case (context, PostStop) =>
    context.log.info("Actor stopped, cleaning up...")
    // Close resources, connections, etc.
    Behaviors.same
}
```

### Watching other actors:

```scala
Behaviors.setup[Command] { context =>
  val child = context.spawn(Worker(), "worker")
  context.watch(child)  // Watch cho Terminated signal

  Behaviors.receive[Command] { (ctx, msg) =>
    // handle messages
    Behaviors.same
  }.receiveSignal {
    case (ctx, Terminated(ref)) =>
      ctx.log.warn(s"Child ${ref.path} terminated!")
      // Có thể spawn lại hoặc xử lý khác
      Behaviors.same
  }
}
```

---

## 9. Actor Hierarchy & Supervision

### Hierarchy:

```
        /system          (system guardian)
        /user            (user guardian — ActorSystem[T])
          │
          ├── /greeter
          ├── /manager
          │     ├── /worker-1
          │     └── /worker-2
          └── /flight-processor
                └── /validator
```

Mỗi actor có **1 parent duy nhất**. Parent chịu trách nhiệm supervision.

### Supervision Strategies:

```scala
import akka.actor.typed.SupervisorStrategy

// 1. Restart — Khởi tạo lại actor, giữ mailbox
Behaviors.supervise(Worker())
  .onFailure[RuntimeException](SupervisorStrategy.restart)

// 2. Stop — Dừng actor
Behaviors.supervise(Worker())
  .onFailure[IllegalStateException](SupervisorStrategy.stop)

// 3. Resume — Bỏ qua lỗi, tiếp tục xử lý message tiếp
Behaviors.supervise(Worker())
  .onFailure[ArithmeticException](SupervisorStrategy.resume)

// 4. Restart với giới hạn (tránh restart loop)
Behaviors.supervise(Worker())
  .onFailure[Exception](
    SupervisorStrategy.restart
      .withLimit(maxNrOfRetries = 3, withinTimeRange = 10.seconds)
  )

// 5. Restart với backoff (production-grade)
Behaviors.supervise(Worker())
  .onFailure[Exception](
    SupervisorStrategy.restartWithBackoff(
      minBackoff = 1.second,
      maxBackoff = 30.seconds,
      randomFactor = 0.2  // jitter để tránh thundering herd
    )
  )
```

### Supervision lồng nhiều exception type:

```scala
Behaviors.supervise(
  Behaviors.supervise(Worker())
    .onFailure[IllegalArgumentException](SupervisorStrategy.resume)
).onFailure[Exception](SupervisorStrategy.restart)
```

---

## 10. Stash — Trì hoãn xử lý message

Khi actor đang ở trạng thái "bận" (loading data, waiting for response), dùng **stash** để giữ message lại xử lý sau.

```scala
object DataLoader {
  sealed trait Command
  case class Load(key: String) extends Command
  case class Query(key: String, replyTo: ActorRef[String]) extends Command
  private case class DataLoaded(data: Map[String, String]) extends Command

  def apply(): Behavior[Command] = Behaviors.withStash(capacity = 100) { stash =>
    Behaviors.setup { context =>
      // Bắt đầu loading data...
      context.pipeToSelf(loadFromDB()) {
        case Success(data) => DataLoaded(data)
        case Failure(ex)   => throw ex
      }

      // Trạng thái LOADING — stash mọi Query
      loading(stash)
    }
  }

  private def loading(stash: StashBuffer[Command]): Behavior[Command] =
    Behaviors.receive { (context, msg) =>
      msg match {
        case Query(_, _) =>
          stash.stash(msg)  // ← Giữ lại, xử lý sau
          Behaviors.same
        case DataLoaded(data) =>
          stash.unstashAll(ready(data))  // ← Xử lý tất cả stashed messages
      }
    }

  private def ready(data: Map[String, String]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Query(key, replyTo) =>
        replyTo ! data.getOrElse(key, "NOT_FOUND")
        Behaviors.same
    }
}
```

---

## 11. Timers & Scheduling

```scala
object Poller {
  sealed trait Command
  case object Poll extends Command
  private case object TimerKey

  def apply(): Behavior[Command] = Behaviors.withTimers { timers =>
    // Gửi Poll mỗi 5 giây
    timers.startTimerWithFixedDelay(TimerKey, Poll, 5.seconds)

    Behaviors.receiveMessage {
      case Poll =>
        println("Polling...")
        Behaviors.same
    }
  }
}
```

### Các loại timer:

```scala
// Fixed delay: chờ 5s SAU KHI xử lý xong message trước
timers.startTimerWithFixedDelay(key, msg, 5.seconds)

// Fixed rate: gửi đều đặn mỗi 5s (bất kể thời gian xử lý)
timers.startTimerAtFixedRate(key, msg, 5.seconds)

// Single shot: gửi 1 lần sau delay
timers.startSingleTimer(key, msg, 10.seconds)

// Cancel timer
timers.cancel(key)

// Check timer active
timers.isTimerActive(key)
```

---

## 12. Interaction Patterns nâng cao

### 12.1 Adapted Response (messageAdapter)

Đã giới thiệu ở mục 7. Dùng khi actor A cần nhận response từ actor B có khác protocol.

### 12.2 Pipe To Self

Chuyển `Future` result thành message cho chính mình:

```scala
object FlightFetcher {
  sealed trait Command
  case class FetchFlight(id: String) extends Command
  private case class FlightResult(flight: Flight) extends Command
  private case class FlightFailed(ex: Throwable) extends Command

  def apply(repo: FlightRepository): Behavior[Command] =
    Behaviors.receive { (context, msg) =>
      msg match {
        case FetchFlight(id) =>
          val futureResult: Future[Flight] = repo.findById(id)

          // Pipe future result back to self
          context.pipeToSelf(futureResult) {
            case Success(flight) => FlightResult(flight)
            case Failure(ex)     => FlightFailed(ex)
          }
          Behaviors.same

        case FlightResult(flight) =>
          context.log.info(s"Got flight: ${flight.id}")
          Behaviors.same

        case FlightFailed(ex) =>
          context.log.error("Failed to fetch flight", ex)
          Behaviors.same
      }
    }
}
```

> **Rule**: KHÔNG BAO GIỜ access actor state từ Future callback. Luôn dùng `pipeToSelf` để đưa result về mailbox.

### 12.3 Per-Session Child Actor

Spawn child actor cho mỗi request phức tạp:

```scala
case class ProcessBooking(booking: Booking) extends Command

Behaviors.receive { (context, msg) =>
  msg match {
    case ProcessBooking(booking) =>
      // Mỗi booking xử lý bởi 1 child actor riêng
      val child = context.spawn(
        BookingProcessor(booking),
        s"booking-${booking.id}"
      )
      Behaviors.same
  }
}
```

---

## 13. Testing Typed Actor

### Setup test (đã có trong `build.sbt`):

```scala
"com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test
```

### Synchronous testing với `BehaviorTestKit`:

```scala
import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox

class GreeterSpec extends AnyWordSpec with Matchers {

  "Greeter" should {
    "reply with Greeted" in {
      val testKit = BehaviorTestKit(Greeter())
      val inbox = TestInbox[Greeter.Greeted]()

      testKit.run(Greeter.Greet("Alice", inbox.ref))

      inbox.expectMessage(Greeter.Greeted("Alice"))
    }
  }
}
```

### Async testing với `ActorTestKit` (recommended cho integration tests):

```scala
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe

class FlightActorSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "FlightActor" should {
    "process flight command" in {
      val probe = testKit.createTestProbe[FlightActor.Response]()
      val actor = testKit.spawn(FlightActor())

      actor ! FlightActor.GetFlight("VN100", probe.ref)

      val response = probe.receiveMessage(3.seconds)
      response shouldBe a[FlightActor.FlightFound]
    }

    "handle unknown flight" in {
      val probe = testKit.createTestProbe[FlightActor.Response]()
      val actor = testKit.spawn(FlightActor())

      actor ! FlightActor.GetFlight("UNKNOWN", probe.ref)

      probe.expectMessage(FlightActor.FlightNotFound("UNKNOWN"))
    }
  }
}
```

### Test log messages:

```scala
import akka.actor.testkit.typed.scaladsl.LoggingTestKit

"log a greeting" in {
  val actor = testKit.spawn(Greeter())
  
  LoggingTestKit.info("Hello Alice!").expect {
    actor ! Greeter.Greet("Alice", testKit.system.ignoreRef)
  }
}
```

---

## 14. EventSourced Actor (Persistence)

Project skyFlow đã có dependency `akka-persistence-typed`. Đây là cách tạo actor lưu trữ state qua events.

```scala
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object FlightBookingActor {
  // Commands
  sealed trait Command
  case class BookSeat(
    passengerId: String,
    seatNumber: String,
    replyTo: ActorRef[Response]
  ) extends Command

  // Events (persisted)
  sealed trait Event
  case class SeatBooked(passengerId: String, seatNumber: String) extends Event

  // State
  case class State(bookedSeats: Map[String, String] = Map.empty) {
    def book(passengerId: String, seat: String): State =
      copy(bookedSeats = bookedSeats + (seat -> passengerId))
    def isSeatAvailable(seat: String): Boolean =
      !bookedSeats.contains(seat)
  }

  // Responses
  sealed trait Response
  case class BookingConfirmed(seatNumber: String) extends Response
  case class SeatUnavailable(seatNumber: String) extends Response

  def apply(flightId: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"flight-$flightId"),
      emptyState = State(),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )

  private val commandHandler: (State, Command) => Effect[Event, State] = {
    (state, command) =>
      command match {
        case BookSeat(passengerId, seat, replyTo) =>
          if (state.isSeatAvailable(seat))
            Effect
              .persist(SeatBooked(passengerId, seat))
              .thenReply(replyTo)(_ => BookingConfirmed(seat))
          else
            Effect.reply(replyTo)(SeatUnavailable(seat))
      }
  }

  private val eventHandler: (State, Event) => State = { (state, event) =>
    event match {
      case SeatBooked(passengerId, seat) =>
        state.book(passengerId, seat)
    }
  }
}
```

### Flow: Command → Validate → Persist Event → Update State → Reply

```
Command ──► commandHandler ──► Effect.persist(Event) ──► eventHandler ──► New State
                                      │
                                      └──► Journal (Disk/DB)
```

---

## 15. Best Practices

### DO ✓

```scala
// 1. Luôn dùng sealed trait cho protocol
sealed trait Command
// Compiler sẽ warn nếu bạn quên handle 1 case

// 2. Đặt protocol trong companion object
object MyActor {
  sealed trait Command
  // ...
  def apply(): Behavior[Command] = ???
}

// 3. Dùng private cho internal messages
private case class InternalResult(data: String) extends Command

// 4. Dùng pipeToSelf cho Future, KHÔNG access state trong callback
context.pipeToSelf(futureCall()) {
  case Success(v) => InternalSuccess(v)
  case Failure(e) => InternalFailure(e)
}

// 5. Dùng Behaviors.supervise cho production actors
Behaviors.supervise(myBehavior)
  .onFailure[Exception](SupervisorStrategy.restartWithBackoff(
    minBackoff = 1.second, maxBackoff = 30.seconds, randomFactor = 0.2
  ))

// 6. Giữ message immutable (case class + val)
final case class CreateFlight(code: String) extends Command
```

### DON'T ✗

```scala
// 1. KHÔNG dùng var bên ngoài Behavior
var mutableState = ???  // ← NGUY HIỂM: race condition

// 2. KHÔNG blocking trong actor
Thread.sleep(1000)                    // ← CHẾT mailbox
Await.result(future, 10.seconds)      // ← CHẾT thread pool

// 3. KHÔNG access actor internals từ Future callback
futureCall().onComplete {
  case Success(v) =>
    context.log.info("Got it")  // ← KHÔNG AN TOÀN
    state = newState             // ← RACE CONDITION
}

// 4. KHÔNG gửi mutable object qua message
case class BadMsg(data: mutable.ListBuffer[String]) extends Command // ← KHÔNG

// 5. KHÔNG catch Exception trong actor — để supervisor xử lý
try { riskyOperation() } catch { case e: Exception => ??? }  // ← anti-pattern
```

### Naming Conventions:

| Thành phần | Convention | Ví dụ |
|-----------|-----------|-------|
| Actor object | `XxxActor` hoặc `Xxx` | `FlightActor`, `Greeter` |
| Commands | `sealed trait Command` | `CreateFlight`, `GetFlight` |
| Events | `sealed trait Event` | `FlightCreated`, `SeatBooked` |
| State | `case class State(...)` | `State(flights: Map[...])` |
| Response | `sealed trait Response` | `FlightFound`, `NotFound` |
| Internal msgs | `private case class` | `private case class Wrapped(...)` |

---

## 16. Bài tập thực hành theo project skyFlow

### Bài 1: Hello Actor (Beginner)
Tạo `GreeterActor` đơn giản nhận `Greet(name)` và log greeting. Test bằng `BehaviorTestKit`.

### Bài 2: Flight Counter (State management)
Tạo `FlightCounterActor` đếm số chuyến bay đã tạo. Commands: `Increment`, `Decrement`, `GetCount(replyTo)`. Quản lý state bằng cách đổi behavior.

### Bài 3: Flight Registry (CRUD Actor)
Tạo `FlightRegistryActor` quản lý danh sách flights:
- `CreateFlight(code, route, replyTo)`
- `GetFlight(code, replyTo)`
- `GetAllFlights(replyTo)`
- `DeleteFlight(code, replyTo)`

### Bài 4: Booking Saga (Actor Hierarchy + Supervision)
Tạo `BookingManager` sinh `BookingProcessor` cho mỗi booking request:
1. `BookingManager` nhận `CreateBooking`
2. Spawn `BookingProcessor` child
3. `BookingProcessor` validate → allocate seat → confirm
4. Supervision: restart nếu lỗi

### Bài 5: Flight Event Sourcing (Persistence)
Dùng `EventSourcedBehavior` cho `FlightPersistenceActor`:
- Command: `ScheduleFlight`, `CancelFlight`, `DelayFlight`
- Event: `FlightScheduled`, `FlightCancelled`, `FlightDelayed`
- State: `FlightState(status, schedule, history)`

### Bài 6: Integration — Kết nối với Akka HTTP
Tạo REST API route dùng Ask pattern để giao tiếp với actors:
```
POST /api/flights      → FlightRegistryActor ! CreateFlight
GET  /api/flights/:id  → FlightRegistryActor ? GetFlight
```

---

## Tài liệu tham khảo

- [Akka Typed Official Docs](https://doc.akka.io/docs/akka/2.6/typed/index.html)
- [Akka Typed Actor Introduction](https://doc.akka.io/docs/akka/2.6/typed/actors.html)
- [Interaction Patterns](https://doc.akka.io/docs/akka/2.6/typed/interaction-patterns.html)
- [Persistence (Event Sourcing)](https://doc.akka.io/docs/akka/2.6/typed/persistence.html)
- [Testing](https://doc.akka.io/docs/akka/2.6/typed/testing.html)
- [Style Guide](https://doc.akka.io/docs/akka/2.6/typed/style-guide.html)

---

## Lộ trình học gợi ý

```
Tuần 1: Mục 1-6   → Hiểu concept, tạo actor đầu tiên
Tuần 2: Mục 7-9   → Ask pattern, lifecycle, supervision
Tuần 3: Mục 10-12 → Stash, timers, patterns nâng cao
Tuần 4: Mục 13-14 → Testing + Persistence
Tuần 5: Bài tập 1-6 → Thực hành trên project skyFlow
```

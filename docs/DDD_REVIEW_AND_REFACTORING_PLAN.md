# DDD Review & Refactoring Plan — SkyFlow

## 1. Đánh giá hiện trạng

### Cấu trúc hiện tại

```
com.akka.learning/
├── Main.scala
├── actors/
│   ├── AllocationActor.scala
│   ├── FlightActor.scala
│   └── FlightRegistry.scala
├── http/
│   ├── AllocationRoutes.scala
│   ├── FlightRoutes.scala
│   └── JsonProtocols.scala
├── models/
│   ├── CborSerializable.scala
│   ├── commands/     ← Akka commands
│   ├── domain/       ← Domain models
│   ├── events/       ← Akka persistence events
│   └── infrastructure/
└── utils/
```

### Những gì đã làm tốt ✅

| Tiêu chí | Đánh giá |
|---|---|
| Event Sourcing | Đã áp dụng đúng với Akka Persistence (FlightActor) |
| CQRS | Tách command/query qua các message types riêng biệt |
| Immutable Domain | Dùng case class, sealed trait — đúng FP style |
| Supervision & Recovery | FlightRegistry có logic recovery từ journal |
| Structured Logging | CorrelationId, MDC propagation |
| Serialization markers | CborSerializable cho persistence |

---

### Vấn đề chính theo DDD ❌

#### 1. Không có Bounded Context rõ ràng

**Vấn đề:** Tất cả code nằm dưới `com.akka.learning` — một flat package duy nhất. Flight, Allocation, Booking trộn lẫn trong `models/domain/`.

**DDD yêu cầu:** Mỗi bounded context (Flight, Allocation, Booking) cần có package riêng với full stack: domain → application → infrastructure → interface.

#### 2. Anemic Domain Model

**Vấn đề:** Domain model gần như chỉ là data holder:

```scala
// Flight chỉ có 3 method đơn giản
case class Flight(...) {
  def occupiedSeats: Int = totalSeats - availableSeats
  def isFullyBooked: Boolean = availableSeats <= 0
  def hasCapacity(seats: Int): Boolean = availableSeats >= seats
}

// AllocationRequest, AllocationResult — không có behavior
// Passenger, BookingRequest — không có validation
```

**DDD yêu cầu:** Domain model phải chứa business logic, validation rules, invariant protection. Ví dụ: `Flight.allocateSeats()` nên trả về `Either[DomainError, Flight]`.

#### 3. Business Logic bị rò rỉ vào Actor Layer

**Vấn đề:**
- `AllocationActor.processAllocation()` chứa logic allocation ← phải ở domain service
- `FlightActor.commandHandler` làm validation ← phải ở domain model
- `FlightRoutes` tự tạo `Flight` object từ HTTP request ← phải ở factory/application service

```scala
// FlightRoutes.scala — HTTP layer tự build domain object
val flight = Flight(
  flightId = flightId,
  flightNumber = request.flightNumber,
  route = flightRoute,
  ...
)
```

```scala
// AllocationRoutes.scala — tạo Airport với empty fields!
val origin = Airport(demand.originCode, "", "", "")
```

#### 4. Không có Aggregate Root pattern

**Vấn đề:**
- `Flight` không protect invariants (ví dụ: `availableSeats` có thể < 0)
- Không có factory method để tạo Flight hợp lệ
- State transition không được domain model kiểm soát

#### 5. "models/" package là God Module

**Vấn đề:** `models/` trộn:
- Domain objects (`domain/`)
- Akka commands (`commands/`) — infrastructure concern
- Akka events (`events/`) — infrastructure concern
- Infrastructure models (`infrastructure/`)

**DDD yêu cầu:** Domain layer không phụ thuộc vào framework. Commands/Events nên là application layer hoặc tách rõ domain events vs persistence events.

#### 6. Không có Value Objects với validation

**Vấn đề:**
- `Airport.code` không validate (IATA 3 letters?)
- `Flight.totalSeats` không validate (> 0?)
- `Passenger.email` không validate format
- String-typed IDs thay vì proper Value Objects

#### 7. Không có Repository Pattern

**Vấn đề:** FlightRegistry vừa là supervisor pattern vừa đóng vai repository. Không có abstraction giữa domain và persistence.

#### 8. Không có Domain Service

**Vấn đề:** Allocation logic nằm trong Actor. Không có pure domain service cho:
- Seat allocation algorithm
- Flight availability check
- Booking validation rules

#### 9. Không có Anti-Corruption Layer

**Vấn đề:** HTTP request DTOs (`CreateFlightRequest`) được define bên trong `JsonProtocols.scala` thay vì tách riêng. Mapping từ external → internal không rõ ràng.

#### 10. SharedDomain là anti-pattern

**Vấn đề:** `SharedDomain.scala` chứa `OTTConnection` phụ thuộc vào `Flight` — tạo coupling giữa contexts.

---

## 2. Cấu trúc DDD đề xuất

```
com.skyflow/
│
├── flight/                              ← Bounded Context: Flight Management
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Flight.scala             # Aggregate Root (rich model)
│   │   │   ├── FlightId.scala           # Value Object
│   │   │   ├── FlightNumber.scala       # Value Object with validation
│   │   │   ├── Airport.scala            # Value Object with validation
│   │   │   ├── AirportCode.scala        # Value Object (IATA 3-letter)
│   │   │   ├── Route.scala              # Value Object
│   │   │   ├── FlightStatus.scala       # Enum Value Object
│   │   │   └── SeatCapacity.scala       # Value Object (totalSeats > 0)
│   │   ├── event/
│   │   │   └── FlightEvent.scala        # Domain Events (pure, no Akka dep)
│   │   ├── error/
│   │   │   └── FlightError.scala        # Domain-specific errors
│   │   └── repository/
│   │       └── FlightRepository.scala   # Port (trait) — no implementation
│   ├── application/
│   │   ├── command/
│   │   │   └── FlightCommands.scala     # Application commands (with ActorRef)
│   │   ├── dto/
│   │   │   └── FlightDto.scala          # CreateFlightRequest, FlightResponse
│   │   └── service/
│   │       └── FlightApplicationService.scala  # Orchestration, DTO → Domain
│   ├── infrastructure/
│   │   ├── actor/
│   │   │   ├── FlightActor.scala        # EventSourcedBehavior (adapter)
│   │   │   └── FlightRegistryActor.scala # Supervisor actor
│   │   └── serialization/
│   │       └── FlightJsonProtocol.scala
│   └── interface/
│       └── http/
│           └── FlightRoutes.scala       # Thin HTTP layer, delegates to app service
│
├── allocation/                           ← Bounded Context: Allocation
│   ├── domain/
│   │   ├── model/
│   │   │   ├── AllocationRun.scala       # Aggregate Root
│   │   │   ├── Allocation.scala          # Entity
│   │   │   ├── ODPair.scala              # Value Object
│   │   │   ├── ODDemand.scala            # Value Object
│   │   │   └── Priority.scala            # Enum Value Object
│   │   ├── event/
│   │   │   └── AllocationEvent.scala     # Domain Events
│   │   ├── error/
│   │   │   └── AllocationError.scala
│   │   └── service/
│   │       └── AllocationDomainService.scala  # Pure allocation algorithm
│   ├── application/
│   │   ├── command/
│   │   │   └── AllocationCommands.scala
│   │   ├── dto/
│   │   │   └── AllocationDto.scala
│   │   └── service/
│   │       └── AllocationApplicationService.scala
│   ├── infrastructure/
│   │   └── actor/
│   │       └── AllocationActor.scala
│   └── interface/
│       └── http/
│           └── AllocationRoutes.scala
│
├── booking/                              ← Bounded Context: Booking (future)
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Booking.scala
│   │   │   ├── Passenger.scala
│   │   │   └── BookingRequest.scala
│   │   └── event/
│   │       └── BookingEvent.scala
│   └── ...
│
└── shared/                               ← Shared Kernel (minimal)
    ├── domain/
    │   └── AirportCode.scala             # Shared Value Object
    └── infrastructure/
        ├── observability/
        │   ├── CorrelationId.scala
        │   ├── Logging.scala
        │   └── Metrics.scala
        └── serialization/
            └── CborSerializable.scala
```

---

## 3. Kế hoạch Refactoring — 6 Phase

### Phase R1: Rich Domain Model (Ưu tiên cao nhất)

**Mục tiêu:** Chuyển domain models từ anemic → rich, thêm validation, invariant protection.

**Tasks:**

1. **Tạo Value Objects với validation:**

```scala
// AirportCode.scala
case class AirportCode private (value: String) extends AnyVal
object AirportCode {
  def apply(code: String): Either[FlightError, AirportCode] =
    if (code.matches("^[A-Z]{3}$")) Right(new AirportCode(code))
    else Left(FlightError.InvalidAirportCode(code))
}

// FlightNumber.scala
case class FlightNumber private (value: String) extends AnyVal
object FlightNumber {
  def apply(number: String): Either[FlightError, FlightNumber] =
    if (number.matches("^[A-Z]{2}\\d{1,4}$")) Right(new FlightNumber(number))
    else Left(FlightError.InvalidFlightNumber(number))
}

// SeatCapacity.scala
case class SeatCapacity private (value: Int) extends AnyVal
object SeatCapacity {
  def apply(seats: Int): Either[FlightError, SeatCapacity] =
    if (seats > 0 && seats <= 853) Right(new SeatCapacity(seats))
    else Left(FlightError.InvalidSeatCapacity(seats))
}
```

2. **Rich Flight Aggregate Root:**

```scala
case class Flight private (
  id: FlightId,
  flightNumber: FlightNumber,
  route: Route,
  scheduledDeparture: LocalDateTime,
  scheduledArrival: LocalDateTime,
  totalSeats: SeatCapacity,
  availableSeats: Int,
  status: FlightStatus
) {
  // Business logic IN the aggregate
  def allocateSeats(count: Int): Either[FlightError, (Flight, SeatsAllocated)] =
    if (!hasCapacity(count)) Left(FlightError.InsufficientSeats(id, count, availableSeats))
    else if (status == FlightStatus.Cancelled) Left(FlightError.FlightCancelled(id))
    else {
      val updated = copy(availableSeats = availableSeats - count)
      val event = SeatsAllocated(id, count, updated.availableSeats)
      Right((updated, event))
    }

  def releaseSeats(count: Int): Either[FlightError, (Flight, SeatsReleased)] = ...
  def changeStatus(newStatus: FlightStatus): Either[FlightError, (Flight, FlightStatusChanged)] = ...
  def cancel(reason: String): Either[FlightError, (Flight, FlightCancelled)] = ...

  def hasCapacity(seats: Int): Boolean = availableSeats >= seats
  def isFullyBooked: Boolean = availableSeats <= 0
}

object Flight {
  // Factory method — duy nhất cách tạo Flight hợp lệ
  def create(
    flightNumber: FlightNumber,
    route: Route,
    departure: LocalDateTime,
    arrival: LocalDateTime,
    totalSeats: SeatCapacity
  ): Either[FlightError, (Flight, FlightCreated)] = {
    if (!arrival.isAfter(departure))
      Left(FlightError.InvalidSchedule("Arrival must be after departure"))
    else {
      val id = FlightId.generate()
      val flight = Flight(id, flightNumber, route, departure, arrival, totalSeats, totalSeats.value, FlightStatus.Scheduled)
      val event = FlightCreated(id, flight)
      Right((flight, event))
    }
  }
}
```

3. **Domain Errors:**

```scala
sealed trait FlightError
object FlightError {
  case class InvalidAirportCode(code: String) extends FlightError
  case class InvalidFlightNumber(number: String) extends FlightError
  case class InvalidSeatCapacity(seats: Int) extends FlightError
  case class InvalidSchedule(reason: String) extends FlightError
  case class InsufficientSeats(flightId: FlightId, requested: Int, available: Int) extends FlightError
  case class FlightCancelled(flightId: FlightId) extends FlightError
  case class FlightAlreadyExists(flightId: FlightId) extends FlightError
}
```

---

### Phase R2: Bounded Context Separation

**Mục tiêu:** Tách code hiện tại thành các bounded context packages.

**Tasks:**

1. Tạo package structure mới:
   - `com.skyflow.flight.domain.model`
   - `com.skyflow.flight.application`
   - `com.skyflow.flight.infrastructure`
   - `com.skyflow.flight.interface.http`
   - Tương tự cho `allocation` và `booking`

2. Di chuyển files:
   - `FlightDomain.scala` → split thành `Flight.scala`, `Airport.scala`, `Route.scala`, `FlightStatus.scala` trong `flight/domain/model/`
   - `AllocationDomain.scala` → split thành `AllocationRun.scala`, `Allocation.scala`, `ODPair.scala` trong `allocation/domain/model/`
   - `PassengerDomain.scala` → `booking/domain/model/`
   - `SharedDomain.scala` → tách và phân bổ vào đúng context

3. Tách `JsonProtocols.scala`:
   - `FlightJsonProtocol.scala` → `flight/infrastructure/serialization/`
   - `AllocationJsonProtocol.scala` → `allocation/infrastructure/serialization/`
   - DTOs (`CreateFlightRequest`, etc.) → `flight/application/dto/`

---

### Phase R3: Application Layer

**Mục tiêu:** Tạo application service layer để tách HTTP layer khỏi domain logic.

**Tasks:**

1. **FlightApplicationService:**

```scala
class FlightApplicationService(flightRepository: FlightRepository) {

  def createFlight(dto: CreateFlightDto): Either[FlightError, Flight] = {
    for {
      flightNumber <- FlightNumber(dto.flightNumber)
      originCode   <- AirportCode(dto.originCode)
      destCode     <- AirportCode(dto.destinationCode)
      totalSeats   <- SeatCapacity(dto.totalSeats)
      origin        = Airport(originCode, dto.originName, dto.originCity, dto.originCountry)
      destination   = Airport(destCode, dto.destinationName, dto.destinationCity, dto.destinationCountry)
      route         = Route(origin, destination, dto.distance, Duration.parse(dto.estimatedDuration))
      result       <- Flight.create(flightNumber, route, dto.scheduledDeparture, dto.scheduledArrival, totalSeats)
    } yield result._1
  }
}
```

2. **AllocationDomainService — pure allocation algorithm:**

```scala
trait AllocationDomainService {
  def allocate(
    demands: List[(ODPair, Int)],
    availableFlights: List[Flight],
    priority: Priority
  ): AllocationResult
}
```

3. Di chuyển `Flight` construction logic ra khỏi `FlightRoutes` → vào `FlightApplicationService`

---

### Phase R4: Infrastructure Adapters / Repository Pattern

**Mục tiêu:** Tách domain khỏi Akka infrastructure qua ports & adapters.

**Tasks:**

1. **Repository Port (trait):**

```scala
// flight/domain/repository/FlightRepository.scala
trait FlightRepository {
  def save(flight: Flight): Future[Either[FlightError, Flight]]
  def findById(id: FlightId): Future[Option[Flight]]
  def findAll(): Future[List[Flight]]
  def findByRoute(origin: AirportCode, destination: AirportCode): Future[List[Flight]]
}
```

2. **Akka Persistence Adapter:**

```scala
// flight/infrastructure/persistence/AkkaFlightRepository.scala
class AkkaFlightRepository(registry: ActorRef[RegistryCommand])
  extends FlightRepository {
  // Delegates to FlightRegistry actor
}
```

3. `FlightActor` chỉ là adapter — command handler gọi `Flight.allocateSeats()` thay vì tự implement logic.

---

### Phase R5: Domain Events vs Persistence Events

**Mục tiêu:** Tách domain events (pure business) khỏi persistence events (Akka-specific).

**Tasks:**

1. **Domain Events (no Akka dependency):**

```scala
// flight/domain/event/FlightEvent.scala
sealed trait FlightDomainEvent {
  def flightId: FlightId
  def occurredAt: LocalDateTime
}
case class FlightCreated(flightId: FlightId, flight: Flight, occurredAt: LocalDateTime) extends FlightDomainEvent
case class SeatsAllocated(flightId: FlightId, count: Int, remaining: Int, occurredAt: LocalDateTime) extends FlightDomainEvent
```

2. **Persistence Events (with CborSerializable):**

```scala
// flight/infrastructure/persistence/FlightPersistenceEvent.scala
sealed trait FlightPersistenceEvent extends CborSerializable
case class FlightCreatedPersistence(flightId: String, flightData: FlightData, timestamp: Long) extends FlightPersistenceEvent
```

3. Tạo mapper giữa domain events ↔ persistence events

---

### Phase R6: Anti-Corruption Layer & Clean Interface

**Mục tiêu:** HTTP layer chỉ map DTO ↔ Domain, không chứa logic.

**Tasks:**

1. **Tạo DTOs riêng:**

```scala
// flight/application/dto/FlightDto.scala
case class CreateFlightRequest(...)
case class FlightResponse(...)
case class FlightListResponse(...)
```

2. **DTO Mapper:**

```scala
object FlightDtoMapper {
  def toDomain(dto: CreateFlightRequest): Either[FlightError, CreateFlightCommand] = ...
  def toResponse(flight: Flight): FlightResponse = ...
}
```

3. **Thin Routes:**

```scala
// Route chỉ: parse request → call app service → map response
post {
  entity(as[CreateFlightRequest]) { request =>
    flightAppService.createFlight(request) match {
      case Right(flight) => complete(StatusCodes.Created, FlightDtoMapper.toResponse(flight))
      case Left(error)   => complete(StatusCodes.BadRequest, ErrorMapper.toResponse(error))
    }
  }
}
```

---

## 4. Thứ tự thực hiện (khuyến nghị)

```
Phase R1: Rich Domain Model              ← Quan trọng nhất, làm trước
  └── Value Objects, Factory methods, Business logic in Aggregate

Phase R2: Bounded Context Separation      ← Restructure packages
  └── Flight/Allocation/Booking contexts

Phase R3: Application Layer               ← Tách orchestration
  └── App Services, DTOs, Command handlers

Phase R4: Repository Pattern              ← Abstract infrastructure
  └── Repository ports, Akka adapters

Phase R5: Domain vs Persistence Events    ← Clean domain layer
  └── Pure domain events, Persistence adapters

Phase R6: Anti-Corruption Layer           ← Clean boundaries
  └── DTO mappers, Thin HTTP routes
```

---

## 5. Tóm tắt đánh giá

| Tiêu chí DDD | Hiện tại | Mục tiêu |
|---|---|---|
| Bounded Context | ❌ Flat package | ✅ Flight / Allocation / Booking |
| Aggregate Root | ❌ Anemic model | ✅ Rich Flight with invariants |
| Value Objects | ❌ Raw primitives | ✅ AirportCode, FlightNumber, etc. |
| Domain Events | ❌ Chỉ có persistence events | ✅ Pure domain events + adapters |
| Domain Services | ❌ Logic trong actors | ✅ AllocationDomainService |
| Repository | ❌ Không có abstraction | ✅ Repository port + Akka adapter |
| Application Service | ❌ Logic trong HTTP routes | ✅ FlightApplicationService |
| Factory Pattern | ❌ Tạo trực tiếp case class | ✅ Flight.create() factory method |
| Anti-Corruption Layer | ❌ DTO trong JsonProtocols | ✅ Separate DTOs + Mappers |
| Ubiquitous Language | ⚠️ Có nhưng chưa nhất quán | ✅ Consistent naming |

**Điểm tổng: 3/10 theo DDD** — Project đang ở mức "Layered Architecture with Event Sourcing" nhưng chưa đạt DDD. Business logic phân tán giữa actors, HTTP routes, và vài domain model methods. Cần refactor significant để đạt DDD chuẩn.

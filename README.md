# SkyFlow - Flight Operations System

A comprehensive learning project demonstrating the **Akka ecosystem** (Akka Typed Actors, Akka HTTP, Akka Streams, and Akka Persistence) using a flight operations management system as the domain.

## 🚀 Technology Stack

- **Scala 2.13.12**
- **JDK 17+**
- **SBT 1.9.7**
- **Akka 2.6.21**
  - Akka Actor Typed
  - Akka Streams
  - Akka HTTP 10.2.10
  - Akka Persistence Typed with JDBC
- **PostgreSQL** - Production-grade persistence backend
- **Structured Logging** (Logback + Logstash encoder)

## ✨ Core Features

### 🛫 Flight Management
- **Flight CRUD Operations**: Create, read, update, and delete flight information
- **Real-time Capacity Tracking**: Monitor available seats vs total capacity
- **Flight Status Management**: Track flight lifecycle (Scheduled → Boarding → Departed → Arrived)
- **Route Management**: Define flight routes with origin, destination, distance, and duration
- **Event Sourcing**: Full audit trail of all flight state changes
- **Stateful Actors**: Each flight managed by a dedicated actor with its own state

### 🗓️ Allocation System
- **Batch Seat Allocation**: Process multiple O&D (Origin-Destination) requests in a single run
- **Priority-based Processing**: Support Low, Medium, High, and Critical priority levels
- **Demand Optimization**: Intelligent seat allocation across available flights
- **Allocation Tracking**: Monitor allocation progress and results
- **Run Correlation**: Track and correlate allocation runs with unique run IDs
- **Unallocated Demand Reporting**: Identify unfulfilled booking requests

### 🔗 OTT (Origin-Terminal-Terminal) Processing
- **Multi-leg Connection Analysis**: Find valid connecting flights via intermediate airports
- **Layover Validation**: Ensure connections have valid layover times (45 min - 6 hours)
- **Total Journey Calculation**: Compute total travel time including layovers
- **Stream Processing**: Handle large volumes of connection possibilities with backpressure

### 📊 O&D (Origin-Destination) Analysis
- **Demand Aggregation**: Aggregate booking requests by origin-destination pairs
- **Statistical Analysis**: Calculate total requests, seats requested, and averages
- **Time Period Reporting**: Analyze demand over configurable time periods
- **Market Intelligence**: Identify high-demand routes for capacity planning

### 👥 Passenger & Booking Management
- **Passenger Registration**: Store passenger details (name, email, ID)
- **Booking Requests**: Create booking requests with seat requirements
- **Booking Confirmation**: Confirm successful seat allocations
- **Booking Cancellation**: Handle cancellations and seat releases
- **Event History**: Full audit trail of all booking lifecycle events

### 🎯 REST API (Phase 3)
- **Flight Endpoints**: GET/POST/PUT/DELETE operations for flights
- **Allocation Endpoints**: Start and monitor allocation runs
- **Query Capabilities**: Find flights by route, status, or availability
- **JSON Serialization**: Standardized request/response formats
- **Error Handling**: Consistent error responses and validation

### 📈 Observability & Monitoring
- **Structured JSON Logging**: Machine-readable logs with Logstash format
- **Correlation ID Tracking**: Trace requests across all components
- **Run ID Context**: Track batch operations from start to finish
- **Performance Metrics**: Measure operation durations and throughput
- **Context Propagation**: Preserve correlation context across async boundaries
- **Multi-checkpoint Tracking**: Monitor performance at different stages

### 🔄 Streaming Pipelines (Phase 4)
- **Backpressure Handling**: Prevent system overload with Akka Streams
- **Parallel Processing**: Configurable parallelism for batch operations
- **Pipeline Composition**: Build complex workflows from simple components
- **Error Recovery**: Supervision strategies for stream failures
- **Long-running Jobs**: Process large datasets efficiently

### 💾 Event Sourcing & Persistence
- **CQRS Pattern**: Separate command and query responsibilities
- **Event Journal**: PostgreSQL-backed event store
- **Event Replay**: Rebuild state from event history
- **Snapshots**: Periodic state snapshots for faster recovery
- **JDBC Integration**: Production-ready persistence with HikariCP connection pooling

### 🏗️ Architecture Patterns
- **Separation of Concerns (SOC)**: Clean module boundaries and single responsibilities
- **Domain-Driven Design (DDD)**: Clear bounded contexts (Flight, Allocation, Passenger)
- **Actor Model**: Message-passing concurrency without shared mutable state
- **Ask Pattern**: Request-response communication between actors
- **Supervision Hierarchies**: Fault tolerance through supervisor strategies
- **Clean Architecture**: Infrastructure separated from business domain

## � Use Cases & Business Scenarios

### Scenario 1: Real-time Flight Booking
```
User → REST API → FlightRegistry → FlightActor → Update Seats → Event Persisted
```
1. Customer requests booking via API
2. FlightRegistry routes request to appropriate FlightActor
3. FlightActor validates capacity and allocates seats
4. SeatsAllocated event is persisted to journal
5. Response returned to customer with confirmation or error

### Scenario 2: Batch Allocation Run
```
Scheduler → AllocationActor → FlightRegistry → Multiple FlightActors → Aggregate Results
```
1. System triggers nightly allocation run for high-demand O&D pairs
2. AllocationActor coordinates requests across multiple flights
3. Seats allocated based on priority and availability
4. Results aggregated with allocated and unallocated demands
5. Performance metrics and correlation tracking throughout

### Scenario 3: OTT Connection Discovery
```
Stream Source → Filter Invalid → Calculate Layovers → Validate Duration → Sink Results
```
1. Stream processes all possible flight combinations
2. Filters connections with invalid layover times
3. Calculates total journey duration
4. Outputs valid multi-leg itineraries
5. Backpressure prevents memory overflow on large datasets

### Scenario 4: O&D Demand Analysis
```
Batch Data → Group by O&D → Aggregate Stats → Generate Report → Persist Results
```
1. Load booking requests from database or stream
2. Group by origin-destination pairs
3. Calculate demand statistics (total requests, average seats)
4. Identify high-demand routes
5. Store insights for capacity planning

### Scenario 5: System Recovery after Crash
```
System Restart → Load Events → Replay FlightActor State → Resume Operations
```
1. Actor system restarts after unexpected shutdown
2. FlightActors reload state from event journal
3. All seat allocations and status changes replayed
4. System continues from exact state before crash
5. No data loss due to event sourcing
## 🎓 What You'll Learn

This project provides hands-on experience with:

### Akka Fundamentals
- ✅ **Message-based Communication**: Replace method calls with asynchronous messages
- ✅ **Actor Lifecycle**: Understand actor creation, supervision, and termination
- ✅ **Typed Actors**: Type-safe actor definitions with Akka Typed
- ✅ **Ask Pattern**: Request-response communication between actors
- ✅ **Supervision Strategies**: Handle failures gracefully (restart, resume, stop, escalate)

### Event Sourcing & CQRS
- ✅ **Event-first Design**: Model changes as events instead of state mutations
- ✅ **Event Replay**: Rebuild state from historical events
- ✅ **CQRS Separation**: Separate commands (writes) from queries (reads)
- ✅ **Persistent Actors**: EventSourcedBehavior for state recovery
- ✅ **Snapshots**: Optimize recovery with periodic state snapshots

### Reactive Streams
- ✅ **Backpressure**: Prevent fast producers from overwhelming slow consumers
- ✅ **Source/Flow/Sink**: Compose stream processing pipelines
- ✅ **Stream Operators**: map, filter, groupBy, fold, and more
- ✅ **Parallel Processing**: Configure parallelism for CPU-bound operations
- ✅ **Error Handling**: Supervision strategies for stream failures

### Production Practices
- ✅ **Structured Logging**: JSON logs for log aggregation tools
- ✅ **Correlation Tracking**: Trace requests across system boundaries
- ✅ **Performance Metrics**: Measure and optimize critical paths
- ✅ **Database Integration**: JDBC persistence with connection pooling
- ✅ **Configuration Management**: Typesafe Config for environment-specific settings

### Software Design Patterns
- ✅ **Separation of Concerns**: Modular architecture with clear boundaries
- ✅ **Domain-Driven Design**: Bounded contexts and ubiquitous language
- ✅ **Clean Architecture**: Dependencies point inward (domain is independent)
- ✅ **Single Responsibility**: Each module has one reason to change
- ✅ **Dependency Inversion**: Abstractions > concrete implementations
## �📋 Project Overview

This project implements a flight operations system that demonstrates:

### Akka Actors & Akka Typed
- Message-based communication between actors
- Supervision strategies for fault tolerance
- Stateful actors replacing traditional "service classes"
- Actor hierarchies and lifecycle management

### Akka HTTP 10.2
- Route DSL (path, get, post, etc.)
- JSON marshalling/unmarshalling
- Standardized exception & rejection handling
- Request correlation tracking

### Akka Streams
- Source/Flow/Sink composition
- Backpressure handling
- Long-running batch pipelines for:
  - **OTT Processing**: Origin-Terminal-Terminal flight connections
  - **O&D Analysis**: Origin-Destination demand aggregation
  - **Allocation Runs**: Seat allocation optimization

### Logging & Observability
- Structured logging with JSON format
- Correlation ID tracking across async boundaries
- Run ID and O&D group context
- Performance benchmarking for APIs and pipelines

## 🏗️ Project Structure

```
skyflow/
├── build.sbt                      # SBT build configuration
├── project/
│   ├── build.properties           # SBT version
│   └── plugins.sbt                # SBT plugins
├── src/
│   ├── main/
│   │   ├── scala/com/akka/learning/
│   │   │   ├── Main.scala         # Application entry point
│   │   │   ├── actors/            # Akka Typed actors (Phase 2)
│   │   │   ├── http/              # Akka HTTP routes (Phase 3)
│   │   │   ├── streams/           # Akka Streams pipelines (Phase 4)
│   │   │   ├── models/            # Domain models, commands, events
│   │   │   │   ├── Domain.scala
│   │   │   │   ├── Commands.scala
│   │   │   │   └── Events.scala
│   │   │   └── utils/             # Logging and metrics utilities
│   │   │       └── CorrelationId.scala
│   │   └── resources/
│   │       ├── application.conf   # Akka configuration
│   │       ├── logback.xml        # Logging configuration
│   │       └── db/
│   │           ├── schema.sql     # PostgreSQL schema
│   │           └── POSTGRESQL_SETUP.md
│   └── test/                      # Test suites (coming soon)
├── setup-postgres.ps1             # PostgreSQL setup script
├── POSTGRESQL.md                  # PostgreSQL quick reference
└── README.md
```

## 🎯 Domain Model

### Core Entities

- **Airport**: Airport location with code, name, city, country
- **Route**: Flight route with origin, destination, distance, duration
- **Flight**: Flight information with capacity, available seats, status
- **Passenger**: Passenger details
- **ODPair**: Origin-Destination pair for demand analysis
- **OTTConnection**: Origin-Terminal-Terminal flight connections
- **AllocationRequest**: Batch allocation request
- **AllocationResult**: Results of seat allocation run

### Flight Status
- Scheduled
- Boarding
- Departed
- Arrived
- Cancelled
- Delayed

## 🛠️ Getting Started

### Prerequisites

- JDK 17 or higher
- SBT 1.9.7 or higher
- PostgreSQL 12+ (for persistence)

### Installation

1. **Setup PostgreSQL database**:
   
   **Quick setup using PowerShell script:**
   ```powershell
   .\setup-postgres.ps1
   ```
   
   **Manual setup:**
   ```powershell
   # Create database and tables
   psql -U postgres -c "CREATE DATABASE akka_flight_ops;"
   psql -U postgres -d akka_flight_ops -f src/main/resources/db/schema.sql
   ```
   
   See [PostgreSQL Setup Guide](src/main/resources/db/POSTGRESQL_SETUP.md) for detailed instructions.

2. **Update database credentials** (if needed):
   
   Edit `src/main/resources/application.conf`:
   ```hocon
   postgres-db {
     user = "postgres"      # Your PostgreSQL username
     password = "postgres"  # Your PostgreSQL password
   }
   ```

3. **Compile the project**:
   ```bash
   sbt compile
   ```

4. **Run the application**:
   ```bash
   sbt run
   ```

5. **Run tests** (coming in later phases):
   ```bash
   sbt test
   ```

### Quick Start
- PostgreSQL persistence configuration

The current implementation (Phase 1) includes:
- ✅ SBT project setup with all dependencies
- ✅ Complete domain models (Flight, Route, Passenger, etc.)
- ✅ Command and Event definitions for actors
- ✅ Structured logging infrastructure
- ✅ Correlation ID tracking utilities
- ✅ Basic "Hello Akka" typed actor verification

Run the application to verify setup:
```bash
sbt run
```

You should see structured JSON logs with the test actor messages.

## 📚 Learning Phases

### Phase 1: Project Setup & Foundation ✅ **COMPLETED**
- SBT project structure
- Domain models and protocols
- Logging infrastructure

### Phase 2: Akka Typed Actors (Next)
- FlightActor: Stateful actor managing flight state
- FlightRegistry: Supervisor managing multiple flights
- AllocationActor: Coordination actor for seat allocation
- Supervision strategies and testing

### Phase 3: Akka HTTP
- REST API for flight management
- JSON serialization
- Error handling
- Request correlation

### Phase 4: Akka Streams
- OTT processing pipeline
- O&D demand aggregation
- Batch allocation with backpressure
- Performance benchmarking

### Phase 5: Akka Persistence
- Event sourcing for flights
- Event sourcing for allocations
- State recovery
- Snapshots

### Phase 6: Observability
- Enhanced metrics collection
- Health check endpoints
- Production-ready configuration

## 🔧 Configuration

Key configuration in `application.conf`:
### Database Configuration
```hocon
postgres-db {
  host = "localhost"
  port = 5432
  database = "akka_flight_ops"
  user = "postgres"
  password = "postgres"
}
```

### Application Configuration
```hocon
app {
  http {
    interface = "0.0.0.0"
    port = 8080
  }
  
  database {
    host = "localhost"
    port = 5432
    name = "akka_flight_ops"
  }
  
  flight {
    max-capacity = 300
    default-allocation-timeout = 30s
  }
  
  stream {
    parallelism = 4
    buffer-size = 100
  }
}
```

### Persistence Configuration
```hocon
akka.persistence {
  journal.plugin = "jdbc-journal"
  snapshot-store.plugin = "jdbc-snapshot-store"
}

jdbc-connection-settings {
  driver = "org.postgresql.Driver"
  url = "jdbc:postgresql://localhost:5432/akka_flight_ops"
  connectionPool = "HikariCP"
  maxConnections = 10 buffer-size = 100
  }
}
```

## 📊 Observability

All logs are structured in JSON format with:
- `correlationId`: Request tracing across async boundaries
- `runId`: Batch run identification
- `odGroup`: O&D grouping for analysis
- `timestamp`: ISO-8601 timestamp
- `level`: Log level
- `message`: Log message

Example log output:
```json
{
  "timestamp": "2026-03-16T10:30:45.123Z",
  "level": "INFO",
  "message": "Test actor created successfully!",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

## 🧪 Testing

Unit tests will be added in Phase 2 using:
- `ActorTestKit` for actor testing
- `ScalaTest` for general testing
- `akka-http-testkit` for HTTP route testing
- `akka-stream-testkit` for stream testing

## 📖 Learning Resources

- [Akka Documentation](https://doc.akka.io/docs/akka/current/)
- [Akka HTTP Documentation](https://doc.akka.io/docs/akka-http/current/)
- [Akka Streams Documentation](https://doc.akka.io/docs/akka/current/stream/)
- [Akka Persistence Documentation](https://doc.akka.io/docs/akka/current/typed/persistence.html)

## 🤝 Contributing

This is a learning project. Feel free to:
- Experiment with different actor patterns
- Add new features to the domain
- Optimize stream processing
- Enhance observability

## 📝 License

This is an educational project for learning purposes.

---

**Status**: Phase 1 Complete ✅ | Ready for Phase 2 (Akka Typed Actors) 🚀

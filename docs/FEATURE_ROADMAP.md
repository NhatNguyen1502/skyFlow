# SkyFlow — Feature Development & Testing Roadmap

Tài liệu này liệt kê các tính năng theo thứ tự phát triển và kiểm thử hợp lý.  
**Base URL:** `http://localhost:8080/api`

---

## Thứ tự ưu tiên tổng quan

```
Phase 1 — Flight CRUD cơ bản        [Features 1–3]   ← bắt đầu tại đây
Phase 2 — Quản lý trạng thái         [Features 4–5]
Phase 3 — Ghế ngồi + Event Recovery  [Features 6–7]  ← milestone event sourcing
Phase 4 — Booking flow               [Features 8–10] ← cần implement mới
Phase 5 — Allocation thật            [Features 11–13] ← cần implement mới
Phase 6 — End-to-end & nâng cao      [Features 14–16]
```

---

## Phase 1 — Quản lý chuyến bay cơ bản

---

### Feature 1: Tạo chuyến bay

**Mục tiêu:** Tạo một chuyến bay mới, kiểm tra actor `FlightActor` được spawn và event `FlightCreated` được persist vào DB.

#### `POST /api/flights`

**Request body:**
```json
{
  "flightNumber": "VN201",
  "originCode": "HAN",
  "originName": "Noi Bai International Airport",
  "originCity": "Hanoi",
  "originCountry": "Vietnam",
  "destinationCode": "SGN",
  "destinationName": "Tan Son Nhat International Airport",
  "destinationCity": "Ho Chi Minh City",
  "destinationCountry": "Vietnam",
  "distance": 1137.0,
  "estimatedDuration": "PT2H",
  "scheduledDeparture": "2026-04-01T08:00:00",
  "scheduledArrival": "2026-04-01T10:00:00",
  "totalSeats": 180
}
```

**Response thành công `201 Created`:**
```json
{
  "flightId": "a1b2c3d4-...",
  "flightNumber": "VN201",
  "route": {
    "origin": { "code": "HAN", "name": "Noi Bai International Airport", "city": "Hanoi", "country": "Vietnam" },
    "destination": { "code": "SGN", "name": "Tan Son Nhat International Airport", "city": "Ho Chi Minh City", "country": "Vietnam" },
    "distance": 1137.0,
    "estimatedDuration": "PT2H"
  },
  "scheduledDeparture": "2026-04-01T08:00:00",
  "scheduledArrival": "2026-04-01T10:00:00",
  "totalSeats": 180,
  "availableSeats": 180,
  "status": "Scheduled"
}
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/flights \
  -H "Content-Type: application/json" \
  -d '{
    "flightNumber": "VN201",
    "originCode": "HAN",
    "originName": "Noi Bai International Airport",
    "originCity": "Hanoi",
    "originCountry": "Vietnam",
    "destinationCode": "SGN",
    "destinationName": "Tan Son Nhat International Airport",
    "destinationCity": "Ho Chi Minh City",
    "destinationCountry": "Vietnam",
    "distance": 1137.0,
    "estimatedDuration": "PT2H",
    "scheduledDeparture": "2026-04-01T08:00:00",
    "scheduledArrival": "2026-04-01T10:00:00",
    "totalSeats": 180
  }'
```

**Tạo thêm 2 chuyến nữa để có đủ dữ liệu cho các test sau:**

```bash
# HAN -> DAD
curl -X POST http://localhost:8080/api/flights \
  -H "Content-Type: application/json" \
  -d '{
    "flightNumber": "VN571",
    "originCode": "HAN",
    "originName": "Noi Bai International Airport",
    "originCity": "Hanoi",
    "originCountry": "Vietnam",
    "destinationCode": "DAD",
    "destinationName": "Da Nang International Airport",
    "destinationCity": "Da Nang",
    "destinationCountry": "Vietnam",
    "distance": 765.0,
    "estimatedDuration": "PT1H20M",
    "scheduledDeparture": "2026-04-01T09:00:00",
    "scheduledArrival": "2026-04-01T10:20:00",
    "totalSeats": 150
  }'

# SGN -> DAD
curl -X POST http://localhost:8080/api/flights \
  -H "Content-Type: application/json" \
  -d '{
    "flightNumber": "VN133",
    "originCode": "SGN",
    "originName": "Tan Son Nhat International Airport",
    "originCity": "Ho Chi Minh City",
    "originCountry": "Vietnam",
    "destinationCode": "DAD",
    "destinationName": "Da Nang International Airport",
    "destinationCity": "Da Nang",
    "destinationCountry": "Vietnam",
    "distance": 605.0,
    "estimatedDuration": "PT1H10M",
    "scheduledDeparture": "2026-04-01T11:00:00",
    "scheduledArrival": "2026-04-01T12:10:00",
    "totalSeats": 120
  }'
```

**Checklist:**
- [ ] Response status `201`
- [ ] `availableSeats == totalSeats` (180)
- [ ] `status == "Scheduled"`
- [ ] `flightId` là UUID hợp lệ
- [ ] DB: bảng `journal` có 1 row với `persistence_id = "flight-{flightId}"`

---

### Feature 2: Lấy danh sách chuyến bay

**Mục tiêu:** Sau khi tạo 3 chuyến, GET trả về đúng 3 chuyến.

#### `GET /api/flights`

**Response thành công `200 OK`:**
```json
{
  "flights": [
    {
      "flightId": "...",
      "flightNumber": "VN201",
      ...
    },
    {
      "flightId": "...",
      "flightNumber": "VN571",
      ...
    },
    {
      "flightId": "...",
      "flightNumber": "VN133",
      ...
    }
  ]
}
```

**curl:**
```bash
curl http://localhost:8080/api/flights
```

**Checklist:**
- [ ] Response status `200`
- [ ] Trả về đúng 3 chuyến đã tạo
- [ ] Trường `flights` là array

---

### Feature 3: Lấy thông tin 1 chuyến bay

**Mục tiêu:** GET 1 chuyến cụ thể, xử lý cả happy path và not-found.

#### `GET /api/flights/{flightId}`

**curl — happy path:**
```bash
# Thay {flightId} bằng ID lấy từ Feature 1
curl http://localhost:8080/api/flights/{flightId}
```

**Response thành công `200 OK`:**
```json
{
  "flightId": "a1b2c3d4-...",
  "flightNumber": "VN201",
  "route": { ... },
  "scheduledDeparture": "2026-04-01T08:00:00",
  "scheduledArrival": "2026-04-01T10:00:00",
  "totalSeats": 180,
  "availableSeats": 180,
  "status": "Scheduled"
}
```

**curl — sad path (ID không tồn tại):**
```bash
curl http://localhost:8080/api/flights/nonexistent-id-12345
```

**Response `404 Not Found`:**
```json
{
  "error": "FlightNotFound",
  "message": "Flight nonexistent-id-12345 not found"
}
```

**Checklist:**
- [ ] ID tồn tại → `200` với đầy đủ thông tin
- [ ] ID không tồn tại → `404` với error message rõ ràng

---

## Phase 2 — Quản lý trạng thái chuyến bay

---

### Feature 4: Cập nhật trạng thái chuyến bay

**Mục tiêu:** Test vòng đời trạng thái `Scheduled → Boarding → Departed → Arrived`. Kiểm tra DB có event `FlightStatusChanged`.

#### `PUT /api/flights/{flightId}/status`

**Request body:**
```json
{ "status": "Boarding" }
```

**Các giá trị `status` hợp lệ:** `Scheduled` | `Boarding` | `Departed` | `Arrived` | `Cancelled` | `Delayed`

**Response thành công `200 OK`:**
```json
{
  "flightId": "a1b2c3d4-...",
  "flightNumber": "VN201",
  "status": "Boarding",
  ...
}
```

**curl — chạy theo thứ tự:**
```bash
# Bước 1: Scheduled → Boarding
curl -X PUT http://localhost:8080/api/flights/{flightId}/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "Boarding" }'

# Bước 2: Boarding → Departed
curl -X PUT http://localhost:8080/api/flights/{flightId}/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "Departed" }'

# Bước 3: Departed → Arrived
curl -X PUT http://localhost:8080/api/flights/{flightId}/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "Arrived" }'
```

**curl — test Delayed:**
```bash
curl -X PUT http://localhost:8080/api/flights/{flightId}/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "Delayed" }'
```

**Checklist:**
- [ ] Mỗi lần đổi trạng thái → `200` với `status` mới
- [ ] `GET /api/flights/{flightId}` sau đó xác nhận đúng trạng thái
- [ ] DB: `journal` có thêm `FlightStatusChanged` event

---

### Feature 5: Huỷ chuyến bay

**Mục tiêu:** Cancel chuyến bay, sau đó GET xác nhận `status = Cancelled`.

#### `DELETE /api/flights/{flightId}`

**curl:**
```bash
curl -X DELETE http://localhost:8080/api/flights/{flightId}
```

**Response thành công `200 OK`:**
```json
{
  "message": "Flight {flightId} cancelled"
}
```

**Verify sau khi cancel:**
```bash
curl http://localhost:8080/api/flights/{flightId}
# Kỳ vọng: status = "Cancelled"
```

**Checklist:**
- [ ] DELETE → `200`
- [ ] GET sau đó → flight vẫn tồn tại với `status = "Cancelled"`
- [ ] DB: `journal` có event `FlightCancelled`

---

## Phase 3 — Quản lý ghế ngồi & Event Sourcing Recovery

---

### Feature 6: Cập nhật ghế trên chuyến bay

**Mục tiêu:** Đặt ghế (giảm `availableSeats`), kiểm tra validation đủ chỗ.

#### `PUT /api/flights/{flightId}/seats`

**Request body:**
```json
{ "seats": 5 }
```

**Response thành công `200 OK`:**
```json
{
  "flightId": "a1b2c3d4-...",
  "flightNumber": "VN201",
  "availableSeats": 175,
  "totalSeats": 180,
  "status": "Scheduled",
  ...
}
```

**curl — happy path (đặt 5 ghế):**
```bash
curl -X PUT http://localhost:8080/api/flights/{flightId}/seats \
  -H "Content-Type: application/json" \
  -d '{ "seats": 5 }'
```

**curl — đặt đúng bằng số ghế còn lại (edge case):**
```bash
# Nếu availableSeats = 175, đặt đúng 175 ghế
curl -X PUT http://localhost:8080/api/flights/{flightId}/seats \
  -H "Content-Type: application/json" \
  -d '{ "seats": 175 }'
# Kỳ vọng: thành công, availableSeats = 0
```

**curl — sad path (đặt vượt quá ghế còn lại):**
```bash
curl -X PUT http://localhost:8080/api/flights/{flightId}/seats \
  -H "Content-Type: application/json" \
  -d '{ "seats": 999 }'
# Kỳ vọng: 400 Bad Request, error = "UpdateSeatsFailed"
```

**Response lỗi `400 Bad Request`:**
```json
{
  "error": "UpdateSeatsFailed",
  "message": "Insufficient seats available"
}
```

**Checklist:**
- [ ] Đặt 5 ghế → `availableSeats` giảm từ 180 xuống 175
- [ ] Đặt đúng số còn lại → `availableSeats = 0`, thành công
- [ ] Đặt vượt → `400` với error rõ ràng
- [ ] DB: `journal` có `SeatsAllocated` event

---

### Feature 7: Xác minh Event Sourcing Recovery (MILESTONE)

**Mục tiêu:** Kiểm tra actor `FlightActor` rebuild đúng state từ event journal sau khi restart.

**Kịch bản:**
1. Tạo chuyến bay mới
2. Cập nhật seats và status vài lần
3. Restart ứng dụng (`Ctrl+C` rồi `sbt run`)
4. GET lại chuyến bay → phải trả về đúng state cuối cùng trước khi restart

**Thực hiện:**
```bash
# Bước 1: Tạo chuyến bay
curl -X POST http://localhost:8080/api/flights \
  -H "Content-Type: application/json" \
  -d '{
    "flightNumber": "VN999",
    "originCode": "HAN",
    "originName": "Noi Bai",
    "originCity": "Hanoi",
    "originCountry": "Vietnam",
    "destinationCode": "SGN",
    "destinationName": "Tan Son Nhat",
    "destinationCity": "Ho Chi Minh City",
    "destinationCountry": "Vietnam",
    "distance": 1137.0,
    "estimatedDuration": "PT2H",
    "scheduledDeparture": "2026-04-15T08:00:00",
    "scheduledArrival": "2026-04-15T10:00:00",
    "totalSeats": 100
  }'
# Lưu lại flightId từ response

# Bước 2: Đặt 30 ghế
curl -X PUT http://localhost:8080/api/flights/{flightId}/seats \
  -H "Content-Type: application/json" \
  -d '{ "seats": 30 }'

# Bước 3: Đổi trạng thái
curl -X PUT http://localhost:8080/api/flights/{flightId}/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "Boarding" }'

# Bước 4: >>> RESTART ứng dụng (Ctrl+C, rồi sbt run) <<<

# Bước 5: Sau khi restart, GET lại
curl http://localhost:8080/api/flights/{flightId}
```

**Kết quả kỳ vọng sau restart:**
```json
{
  "flightId": "...",
  "flightNumber": "VN999",
  "totalSeats": 100,
  "availableSeats": 70,
  "status": "Boarding"
}
```

**Checklist:**
- [ ] `availableSeats = 70` (100 - 30), KHÔNG phải 100
- [ ] `status = "Boarding"`, KHÔNG phải `"Scheduled"`
- [ ] Actor đọc từ snapshot và/hoặc replay events thành công
- [ ] Không có lỗi trong log khi replay

---

## Phase 4 — Booking Flow *(cần implement mới)*

> **Lưu ý:** `BookingEvents` và `PassengerDomain` đã được định nghĩa. Cần implement thêm:  
> `BookingActor` (EventSourcedBehavior), `BookingRoutes`, và các commands/responses tương ứng.

---

### Feature 8: Tạo booking (đặt vé)

**Mục tiêu:** Passenger đặt vé, `FlightActor` giảm ghế, event `BookingCreated + BookingConfirmed` được persist.

#### `POST /api/bookings`

**Request body:**
```json
{
  "passenger": {
    "passengerId": "pax-001",
    "firstName": "Nguyen",
    "lastName": "Van A",
    "email": "nguyen.vana@example.com"
  },
  "flightId": "{flightId}",
  "requestedSeats": 2
}
```

**Response thành công `201 Created`:**
```json
{
  "bookingId": "bk-...",
  "passengerId": "pax-001",
  "flightId": "{flightId}",
  "flightNumber": "VN201",
  "seatsBooked": 2,
  "status": "Confirmed",
  "createdAt": "2026-04-01T07:30:00"
}
```

**Checklist:**
- [ ] `201` với `bookingId` mới
- [ ] `GET /api/flights/{flightId}` → `availableSeats` giảm đúng 2
- [ ] DB: `journal` có `BookingCreated`, `BookingConfirmed`, `SeatsAllocated`

---

### Feature 9: Xem thông tin booking

#### `GET /api/bookings/{bookingId}`

**curl:**
```bash
curl http://localhost:8080/api/bookings/{bookingId}
```

**Response `200 OK`:**
```json
{
  "bookingId": "bk-...",
  "passenger": {
    "passengerId": "pax-001",
    "firstName": "Nguyen",
    "lastName": "Van A",
    "email": "nguyen.vana@example.com"
  },
  "flightId": "{flightId}",
  "flightNumber": "VN201",
  "seatsBooked": 2,
  "status": "Confirmed"
}
```

**Checklist:**
- [ ] `200` với đầy đủ thông tin passenger + flight + seats
- [ ] ID không tồn tại → `404`

---

### Feature 10: Huỷ booking

**Mục tiêu:** Huỷ booking, `FlightActor` trả lại ghế (`ReleaseSeats`), event `BookingCancelled + SeatsReleased` được persist.

#### `DELETE /api/bookings/{bookingId}`

**curl:**
```bash
curl -X DELETE http://localhost:8080/api/bookings/{bookingId}
```

**Response `200 OK`:**
```json
{
  "message": "Booking {bookingId} cancelled successfully"
}
```

**Verify sau khi cancel:**
```bash
# availableSeats phải tăng lại 2
curl http://localhost:8080/api/flights/{flightId}
```

**Checklist:**
- [ ] `200` sau khi cancel
- [ ] `availableSeats` tăng lại đúng số ghế đã đặt
- [ ] DB: `journal` có `BookingCancelled` và `SeatsReleased`

---

## Phase 5 — Allocation *(cần implement logic thật)*

> **Lưu ý:** `AllocationActor` hiện là **stub** — tất cả O&D pairs đều vào `unallocated`.  
> Cần implement logic: tìm chuyến bay theo route, kiểm tra `availableSeats`, gọi `UpdateFlightSeats`.

---

### Feature 11: Start allocation run

**Mục tiêu:** Gửi yêu cầu phân bổ ghế cho nhiều O&D pairs, nhận ngay `allocationId`.

#### `POST /api/allocations`

**Request body:**
```json
{
  "runId": "run-2026-04-01-001",
  "odPairs": [
    { "originCode": "HAN", "destinationCode": "SGN", "demand": 50 },
    { "originCode": "HAN", "destinationCode": "DAD", "demand": 30 },
    { "originCode": "SGN", "destinationCode": "DAD", "demand": 20 }
  ],
  "priority": "High"
}
```

**Các giá trị `priority` hợp lệ:** `Low` | `Medium` | `High` | `Critical`

**Response `202 Accepted`:**
```json
{
  "allocationId": "alloc-...",
  "status": "started",
  "message": "Allocation run run-2026-04-01-001 started with ID alloc-..."
}
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/allocations \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "run-2026-04-01-001",
    "odPairs": [
      { "originCode": "HAN", "destinationCode": "SGN", "demand": 50 },
      { "originCode": "HAN", "destinationCode": "DAD", "demand": 30 },
      { "originCode": "SGN", "destinationCode": "DAD", "demand": 20 }
    ],
    "priority": "High"
  }'
```

**Checklist:**
- [ ] `202` ngay lập tức (không chờ xử lý xong)
- [ ] `allocationId` hợp lệ trong response

---

### Feature 12: Check allocation status

#### `GET /api/allocations/{allocationId}/status`

**curl:**
```bash
curl http://localhost:8080/api/allocations/{allocationId}/status
```

**Response khi đang xử lý `200 OK`:**
```json
{
  "allocationId": "alloc-...",
  "status": "in-progress",
  "message": "50.0% complete"
}
```

**Response khi hoàn thành `200 OK`:**
```json
{
  "allocationId": "alloc-...",
  "status": "completed",
  "message": "Allocation completed with 3 allocations"
}
```

**Checklist:**
- [ ] Poll ngay sau start → `in-progress` hoặc `completed`
- [ ] Poll sau vài giây → `completed`

---

### Feature 13: Lấy kết quả allocation đầy đủ

#### `GET /api/allocations/{allocationId}`

**curl:**
```bash
curl http://localhost:8080/api/allocations/{allocationId}
```

**Response `200 OK` (sau khi hoàn thành):**
```json
{
  "allocationId": "alloc-...",
  "runId": "run-2026-04-01-001",
  "allocations": [
    {
      "odPair": {
        "origin": { "code": "HAN", "name": "", "city": "", "country": "" },
        "destination": { "code": "SGN", "name": "", "city": "", "country": "" }
      },
      "flight": { "flightId": "...", "flightNumber": "VN201", ... },
      "seatsAllocated": 50
    }
  ],
  "unallocated": [],
  "processingTimeMs": 42,
  "completedAt": "2026-04-01T07:35:00"
}
```

**Checklist:**
- [ ] `allocations` chứa đầy đủ 3 O&D pairs (khi có đủ ghế)
- [ ] `unallocated` rỗng khi tất cả phân bổ thành công
- [ ] Khi không đủ ghế → pair vào `unallocated`

---

## Phase 6 — End-to-end Scenario & Nâng cao

---

### Feature 14: End-to-end scenario hoàn chỉnh

**Kịch bản:** Mô phỏng đầy đủ từ tạo hãng → đặt vé → chuyến bay hoàn thành.

```
1. Tạo 3 chuyến bay (HAN↔SGN, HAN↔DAD, SGN↔DAD)
2. Tạo 5 bookings (mỗi booking 2 ghế)
3. Chạy 1 allocation run
4. Đổi trạng thái: Scheduled → Boarding → Departed → Arrived
5. Verify tổng số ghế đã chiếm đúng = seats từ booking + seats từ allocation
6. Restart app → verify toàn bộ state được recovery đúng
```

---

### Feature 15: OTT Connection (chuyến bay nối tiếp)

**Mục tiêu:** Tìm kết nối HAN→DAD→SGN (layover hợp lệ 45 phút – 6 giờ).

#### `GET /api/connections?origin=HAN&destination=SGN` *(cần implement)*

**Query params:** `origin`, `destination`

**Response kỳ vọng `200 OK`:**
```json
{
  "connections": [
    {
      "origin": { "code": "HAN", ... },
      "firstTerminal": { "code": "DAD", ... },
      "secondTerminal": null,
      "firstFlight": { "flightId": "...", "flightNumber": "VN571" },
      "secondFlight": { "flightId": "...", "flightNumber": "VN133" },
      "layoverDuration": "PT1H40M",
      "totalDuration": "PT3H"
    }
  ]
}
```

**Checklist:**
- [ ] Layover phải trong khoảng 45 phút – 6 giờ (`OTTConnection.isValid`)
- [ ] `totalDuration = firstFlight.duration + layoverDuration + secondFlight.duration`

---

### Feature 16: Demand analysis

**Mục tiêu:** Thống kê booking theo O&D, tổng hợp từ event journal.

#### `GET /api/flights/{flightId}/demand` *(cần implement)*

**Response kỳ vọng `200 OK`:**
```json
{
  "flightId": "...",
  "flightNumber": "VN201",
  "odPair": { "origin": { "code": "HAN" }, "destination": { "code": "SGN" } },
  "totalRequests": 5,
  "totalSeatsRequested": 10,
  "averageSeatsPerRequest": 2.0,
  "periodStart": "2026-04-01T00:00:00",
  "periodEnd": "2026-04-01T23:59:59"
}
```

---

## Chú thích chung

| Trường | Format | Ví dụ |
|---|---|---|
| `estimatedDuration` | ISO-8601 Duration | `"PT2H"`, `"PT1H30M"`, `"PT45M"` |
| `scheduledDeparture` | ISO datetime (no timezone) | `"2026-04-01T08:00:00"` |
| `status` | String enum | `"Scheduled"`, `"Boarding"`, `"Departed"`, `"Arrived"`, `"Cancelled"`, `"Delayed"` |
| `priority` | String enum | `"Low"`, `"Medium"`, `"High"`, `"Critical"` |

**DB verification queries:**
```sql
-- Xem tất cả events của 1 flight
SELECT ordering, persistence_id, sequence_number FROM journal
WHERE persistence_id = 'flight-{flightId}'
ORDER BY sequence_number;

-- Xem tất cả snapshots
SELECT persistence_id, sequence_number, created FROM snapshot;

-- Đếm events theo loại actor
SELECT SPLIT_PART(persistence_id, '-', 1) as actor_type, COUNT(*) as event_count
FROM journal
GROUP BY actor_type;
```

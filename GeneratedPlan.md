# 🎯 Implementation Plan — High-Scale Seat Reservation System

![Architecture Diagram](/Users/sid/.gemini/antigravity-ide/brain/b9832e84-c99e-4835-b436-7cf6610a5fe0/architecture.png)

---

## Architecture Summary (from your diagram)

| Layer | Tech | Role |
|---|---|---|
| Frontend | Cloudflare Pages | Static UI, token derivation, SSL termination, DDoS protection |
| Load Balancer | HAProxy (Round Robin) | Distribute across app instances |
| Application | Spring Boot 3.x + Java 21 Virtual Threads (×2 instances) | Auth, Admission (Lua), Kafka produce, SSE emit |
| Atomic Gatekeeper | Redis 7 (single instance) | Lua script = the atomic decision point. ZSET for hold expiry |
| Async Buffer | Kafka (KRaft mode) | Write-behind to protect Postgres from stampede |
| Source of Truth | PostgreSQL 16 | Durable storage, reconciliation anchor |
| Background Sweeper | Spring `@Scheduled` (or standalone) | Poll Redis ZSET every 1s, emit expiry events |
| Observability | Prometheus + Grafana | Metrics scraping & dashboards |
| Live State | Redis Pub/Sub → SSE | Real-time seat status to browsers |

---

## Phase 0 — Project Scaffolding & Infrastructure

### 0.1 Spring Boot Project Init
- Spring Boot 3.3+ with Java 21
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-redis`, `spring-boot-starter-data-jpa`, `spring-kafka`, `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `spring-boot-starter-validation`, `spring-boot-starter-security` (minimal — token-based)
- Enable Virtual Threads: `spring.threads.virtual.enabled=true`
- Structured JSON logging via Logback + `logstash-logback-encoder`

### 0.2 Docker Compose (Local Dev)
```
services:
  postgres:     (16-alpine, port 5432)
  redis:        (7-alpine, port 6379)
  kafka:        (bitnami/kafka KRaft, port 9092)
  app-1:        (Spring Boot instance 1, port 8081)
  app-2:        (Spring Boot instance 2, port 8082)
  haproxy:      (Round-robin over app-1, app-2, port 8080)
  prometheus:   (scrape /actuator/prometheus)
  grafana:      (dashboards, port 3000)
```

### 0.3 Database Migration (Flyway)
```sql
-- V1__init.sql
CREATE TABLE users (
    user_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       TEXT NOT NULL UNIQUE,
    token_hash  TEXT NOT NULL UNIQUE
);

CREATE TABLE shows (
    show_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    total_seats INT  NOT NULL,
    price BIGINT NOT NULL,  -- integer minor units, never float
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    show_id        UUID NOT NULL REFERENCES shows(show_id),
    seat_id        TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'available'
                   CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id UUID,
    PRIMARY KEY (show_id, seat_id)
);
CREATE INDEX idx_seats_show_status ON seats(show_id, status);

CREATE TABLE reservations (
    reservation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id        UUID NOT NULL REFERENCES shows(show_id),
    user_id        UUID NOT NULL REFERENCES users(user_id),
    seats          TEXT[] NOT NULL,
    amount   BIGINT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'held'
                   CHECK (status IN ('held', 'confirmed', 'cancelled', 'expired')),
    idempotency_key TEXT NOT NULL,
    expires_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (idempotency_key)
);
CREATE INDEX idx_reservations_user_show ON reservations(user_id, show_id);
```

> [!IMPORTANT]
> **`idempotency_key` has a UNIQUE constraint in Postgres** — this is the durable backstop. Redis NX is the fast-path; Postgres UNIQUE is the safety net if Redis loses state.

### 0.4 Redis Key Schema

| Key Pattern | Type | Purpose |
|---|---|---|
| `seat:{show_id}:{seat_id}` | STRING | Value = `reservation_id`. Set via Lua `NX` = the atomic claim |
| `idemp:{idempotency_key}` | STRING | Value = `reservation_id`. Set via `NX` = exactly-once gate |
| `user_count:{show_id}:{user_id}` | STRING (counter) | `INCRBY` inside Lua, checked against `per_user_limit` |
| `hold_expiry` | ZSET | Score = expiry epoch ms, member = `show_id:seat_id:reservation_id` |
| `show_state:{show_id}` | Pub/Sub channel | Publish seat-status changes for SSE |

---

## Phase 1 — Authentication & Authorization

### 1.1 Token Scheme
- **Admin**: Pre-shared bearer token (env var `ADMIN_TOKEN`) for `POST /shows`.
- **Users**: Each user gets a bearer token. Token → `SHA-256 hash` → lookup in `users.token_hash`.
- A `SecurityFilter` (servlet filter or Spring Security) extracts the token, resolves the `user_id`, and sets it on the request context.
- **Key rule**: Identity is ALWAYS from the token. Any `user_id` in the request body is ignored.

### 1.2 Endpoint Authorization
| Endpoint | Who |
|---|---|
| `POST /shows` | Admin only |
| `POST /shows/{id}/reserve` | Authenticated user |
| `POST /reservations/{id}/cancel` | Owner of the reservation only |
| `GET /shows/{id}` | Any authenticated user |
| `GET /reservations/{id}` | Owner only |
| `GET /health/live`, `GET /health/ready` | Public (no auth) |
| `GET /actuator/prometheus` | Public (metrics scrape) |

### 1.3 User Seeding
- A `POST /users/register` endpoint or a seed script that creates test users and returns their tokens.
- For the burst test, pre-create ~500 users.

---

## Phase 2 — The Atomic Admission Layer (Redis Lua Script) ⚡

> [!IMPORTANT]
> **This is THE critical design decision.** The Lua script running atomically inside Redis is where the single-winner seat claim, idempotency check, and per-user-limit enforcement all happen in ONE atomic step. No read-then-write race is possible because Redis executes the entire Lua script without interleaving.

### 2.1 The Lua Script: `reserve_seats.lua`

```
Input KEYS: [idemp_key, user_count_key, seat_key_1, seat_key_2, ...]
Input ARGV: [reservation_id, user_id, per_user_limit, num_seats, hold_ttl_seconds]

Step 1 — Idempotency check
  val = GET idemp:{idempotency_key}
  if val exists → return {"IDEMPOTENT_HIT", existing_reservation_id}

Step 2 — Per-user limit check
  current_count = GET user_count:{show_id}:{user_id} or 0
  if current_count + num_seats > per_user_limit
    → return {"USER_LIMIT_EXCEEDED", current_count}

Step 3 — Atomic seat claim (ALL-OR-NOTHING)
  for each seat_key in KEYS[3..]:
    result = SET seat_key reservation_id NX EX hold_ttl
    if result == nil:
      -- rollback all previously set seats in this loop
      for each already_set_key: DEL already_set_key
      → return {"SEAT_TAKEN", failed_seat_id}

Step 4 — Commit
  SET idemp:{idempotency_key} reservation_id EX (hold_ttl + buffer)
  INCRBY user_count:{show_id}:{user_id} num_seats
  for each seat: ZADD hold_expiry (now + hold_ttl_ms) "show_id:seat_id:reservation_id"
  → return {"OK", reservation_id}
```

**Why this is race-free:**
- Redis is single-threaded; the Lua script executes atomically.
- `SET NX` is the conditional write — if the key already exists, the seat is taken.
- All-or-nothing: if any seat fails, we rollback the ones we already claimed.
- Idempotency and user-limit are checked inside the same atomic block.

### 2.2 Reservation Semantics
- **All-or-nothing**: If a user requests `["A12", "A13"]` and A12 is taken, the entire request fails with 409. No partial booking.
- Document this clearly in the API response.

### 2.3 Hold vs Confirmed
- On successful Lua execution, the reservation starts in `"confirmed"` status (no hold → confirm two-phase needed per the problem statement which says `status: "confirmed"` in the response).
- **Alternative (from your diagram)**: Use a hold model with time-boxed expiry:
  - Lua claims seat with TTL → status = `held`
  - Separate confirm step, or auto-confirm immediately
  - ZSET sweeper expires stale holds

> [!NOTE]
> **Design choice**: Given the problem statement expects `"status": "confirmed"` in the 201 response, I recommend **instant-confirm with a cancellation window** rather than a two-phase hold→confirm flow. The Redis claim IS the confirmation. Kafka write-behind persists it to Postgres asynchronously. The ZSET expiry mechanism is still useful if you want to add a hold model later. Let me know if you prefer hold-then-confirm.

---

## Phase 3 — API Layer (Spring Boot Controllers)

### 3.1 `POST /shows` — Create Show

```
Controller → Service:
  1. Validate request (name, seats[], price)
  2. INSERT into shows table
  3. Bulk INSERT into seats table (all "available")
  4. Pre-warm Redis: no keys needed (absence of seat key = available)
  5. Return 201 with show details + all seats
```

### 3.2 `POST /shows/{id}/reserve` — Reserve Seats

```
Controller → Service:
  1. Extract user_id from SecurityContext (token-derived)
  2. Validate: show exists, seats are valid seat IDs, idempotency_key present
  3. Generate reservation_id (UUID)
  4. Execute Redis Lua script (Phase 2)
     ├─ IDEMPOTENT_HIT → return 200 with original reservation (fetch from Postgres/Redis)
     ├─ USER_LIMIT_EXCEEDED → return 409 {"error": "per_user_limit_exceeded"}
     ├─ SEAT_TAKEN → return 409 {"error": "seat_taken", "seat": "A12"}
     └─ OK → continue
  5. Produce Kafka message: {reservation_id, show_id, user_id, seats, amount, status, idemp_key}
  6. Publish to Redis Pub/Sub channel show_state:{show_id}
  7. Increment Prometheus counters
  8. Return 201 with reservation
```

> [!TIP]
> **Response before Kafka ack**: The 201 is returned after the Redis Lua script succeeds (the atomic claim is done). The Kafka produce is fire-and-forget from the user's perspective. If Kafka is down, the reservation is still valid in Redis; a reconciliation job will catch up.

### 3.3 `POST /reservations/{id}/cancel` — Cancel Reservation

```
Controller → Service:
  1. Extract user_id from token
  2. Fetch reservation from Redis cache or Postgres
  3. Verify owner (reservation.user_id == token user_id), else 403
  4. Verify status is cancellable (held/confirmed, not already cancelled)
  5. Redis: DEL seat keys, DECRBY user_count, ZREM from hold_expiry
  6. Produce Kafka cancel event
  7. Publish to Redis Pub/Sub
  8. Return 200 with updated reservation
```

### 3.4 `GET /shows/{id}` — Show State

```
Controller → Service:
  1. Fetch show from Postgres
  2. Fetch all seats for show (from Postgres, or Redis for hot path)
  3. Compute counts: available, held, confirmed
  4. Assert reconciliation invariant: available + held + confirmed == total_seats
  5. Return show with seat map and counts
```

### 3.5 `GET /reservations/{id}` — Reservation Details

```
Controller → Service:
  1. Extract user_id from token
  2. Fetch reservation, verify owner
  3. Return reservation details
```

### 3.6 Global Exception Handler
- Map all domain exceptions to 4xx (never 5xx for business logic):
  - `SeatTakenException` → 409
  - `UserLimitExceededException` → 409
  - `IdempotencyConflictException` → 409
  - `ReservationNotFoundException` → 404
  - `UnauthorizedException` → 401 / 403
- Only infrastructure failures (Redis down, DB down) → 503
- Every response includes `X-Request-Id` correlation header

---

## Phase 4 — Kafka Write-Behind & Consumer

### 4.1 Kafka Topics

| Topic | Purpose | Partitions |
|---|---|---|
| `reservations` | Confirmed/cancelled reservation events | 6 (keyed by show_id for ordering) |
| `seat-expirations` | Hold expiry events from background sweeper | 3 |
| `reservations-dlq` | Failed messages for replay | 1 |

### 4.2 Producer (in Spring Boot App)
- Produce after successful Redis Lua execution
- Key = `show_id` (ensures all events for a show go to the same partition → ordered)
- Acks = `all` for durability
- On Kafka failure: log, increment `kafka_produce_failures` counter, rely on reconciliation

### 4.3 Consumer / DB Worker
- Separate Spring Boot application (or `@KafkaListener` in the same app)
- Consumes `reservations` topic:
  ```
  For each event:
    1. BEGIN TRANSACTION
    2. INSERT INTO reservations (...) ON CONFLICT (idempotency_key) DO NOTHING
    3. UPDATE seats SET status = event.status, reservation_id = event.reservation_id
       WHERE show_id = ? AND seat_id = ? AND (status = 'available' OR reservation_id = event.reservation_id)
    4. COMMIT
  ```
- **Consumer idempotency**: The `ON CONFLICT DO NOTHING` on `idempotency_key` makes re-processing safe
- On failure: send to DLQ, increment `kafka_consumer_failures` counter

> [!WARNING]
> **Critical**: The Kafka consumer must handle the case where Redis claimed a seat but Postgres already has a conflicting row (e.g., from a previous un-acked message replay). The `ON CONFLICT` and the `WHERE` guard on the `UPDATE` handle this.

---

## Phase 5 — Background Sweeper (Hold Expiry)

### 5.1 Sweeper Logic (`@Scheduled(fixedDelay = 1000)`)
```
Every 1 second:
  1. ZRANGEBYSCORE hold_expiry 0 {now_epoch_ms} LIMIT 100
  2. For each expired member (show_id:seat_id:reservation_id):
     a. Check if seat key still exists in Redis and value matches reservation_id
        (if not, seat was already cancelled/re-assigned — skip)
     b. DEL seat:{show_id}:{seat_id}
     c. DECRBY user_count:{show_id}:{user_id} 1
     d. ZREM hold_expiry member
     e. Produce Kafka expiration event
     f. Publish to Redis Pub/Sub
  3. Increment prometheus counter: seats_expired_total
```

### 5.2 Leader Election (if multiple instances)
- Use Redis `SET NX` with TTL as a simple leader lock: `sweeper_lock` with 2s TTL
- Only the lock holder runs the sweep
- Or: run sweeper as a dedicated single-instance container

---

## Phase 6 — SSE Live State (Real-Time Updates)

### 6.1 Redis Pub/Sub → SSE
- When a seat status changes (reserved, cancelled, expired), publish to `show_state:{show_id}` channel
- Spring Boot SSE endpoint: `GET /shows/{id}/stream`
- `SseEmitter` per client, fed by a Redis Pub/Sub subscriber
- Handles multiple Spring Boot instances cleanly (Redis Pub/Sub is broadcast)

### 6.2 Event Payload
```json
{
  "type": "seat_update",
  "show_id": "...",
  "seat_id": "A12",
  "status": "confirmed",
  "timestamp": "..."
}
```

---

## Phase 7 — Observability Stack

### 7.1 Prometheus Metrics (via Micrometer)

| Metric | Type | Labels |
|---|---|---|
| `reservations_total` | Counter | `status={confirmed,declined}`, `decline_reason={seat_taken,user_limit,idempotent_replay}` |
| `seats_available` | Gauge | `show_id` |
| `seats_held` | Gauge | `show_id` |
| `seats_confirmed` | Gauge | `show_id` |
| `reservation_latency_seconds` | Histogram | `outcome` |
| `redis_lua_duration_seconds` | Histogram | — |
| `kafka_produce_failures_total` | Counter | — |
| `kafka_consumer_lag` | Gauge | `topic`, `partition` |
| `http_server_requests` | Timer (auto) | `method`, `uri`, `status` |
| `seats_expired_total` | Counter | `show_id` |
| `reconciliation_drift` | Gauge | `show_id` (should always be 0) |

### 7.2 Structured Logging
```json
{
  "timestamp": "2026-10-02T01:20:00Z",
  "level": "INFO",
  "logger": "ReservationService",
  "message": "Seat reserved",
  "request_id": "abc-123",
  "user_id": "user-456",
  "show_id": "show-789",
  "seats": ["A12"],
  "outcome": "confirmed",
  "lua_duration_ms": 2
}
```
- Every request gets a `X-Request-Id` (UUID) set in a filter, propagated through MDC

### 7.3 Health Endpoints
- **Liveness**: `GET /health/live` → 200 if JVM is up (always succeeds unless process is wedged)
- **Readiness**: `GET /health/ready` → checks Redis `PING`, Postgres `SELECT 1`, Kafka broker connectivity. Returns 503 if any dependency is down (fail closed)

### 7.4 Grafana Dashboards
- **Burst Dashboard**: RPS, latency p50/p95/p99, confirmed vs declined (stacked area), 5xx count
- **Seat State Dashboard**: Available/held/confirmed gauges per show, reconciliation drift
- **Infrastructure**: Redis command latency, Kafka consumer lag, Postgres connections, JVM heap

---

## Phase 8 — Burst Test Script

### 8.1 `burst.sh` / Go Program
A Go program (for real concurrency, not just async) or a shell script using `hey`/`wrk`:

```
Usage: ./burst <BASE_URL> <NUM_USERS> <CONCURRENCY> <SHOW_ID>

Steps:
  1. Create a show with 100 seats (A1-A100)
  2. Register 500 test users, get their tokens
  3. Fire 20,000 concurrent reserve requests:
     - 5,000 targeting seat A1 (hot seat storm)
     - 5,000 targeting seat A2 (hot seat storm)
     - 10,000 spread across A3-A100 (warm seats)
     - Include idempotency key retries (~10% of requests)
  4. Collect results
  5. Print:
     ┌─────────────────────────────────────┐
     │ 201 Confirmed:        487           │
     │ 409 Seat Taken:      18,200         │
     │ 409 User Limit:        800          │
     │ 409 Idemp Replay:      500          │
     │ 5xx Errors:              0  ✅      │
     ├─────────────────────────────────────┤
     │ Reconciliation:                     │
     │   Available:  0                     │
     │   Held:       0                     │
     │   Confirmed:  100                   │
     │   Total:      100  ✅ (== N)       │
     │   Double-sells: 0  ✅              │
     └─────────────────────────────────────┘
```

---

## Phase 9 — Containerization & Deployment

### 9.1 Dockerfile (Multi-stage)
```dockerfile
FROM eclipse-temurin:21-jdk AS build
COPY . /app
WORKDIR /app
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 9.2 Docker Compose (Production-like)
```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: seatbooking
      POSTGRES_USER: app
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app"]

  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 256mb --maxmemory-policy noeviction
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]

  kafka:
    image: bitnami/kafka:3.7
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      # ... KRaft mode config

  app-1:
    build: .
    depends_on: [postgres, redis, kafka]
    environment:
      SPRING_PROFILES_ACTIVE: prod
      # ... all env vars

  app-2:
    build: .
    depends_on: [postgres, redis, kafka]

  haproxy:
    image: haproxy:2.9-alpine
    ports: ["8080:8080"]
    volumes:
      - ./haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro
    depends_on: [app-1, app-2]

  prometheus:
    image: prom/prometheus:v2.51.0
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro

  grafana:
    image: grafana/grafana:10.4.0
    ports: ["3000:3000"]
    volumes:
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards
      - ./grafana/datasources:/etc/grafana/provisioning/datasources
```

### 9.3 Compute Instance Deployment (OCI/AWS)
1. Provision a VM (e.g., OCI `VM.Standard.A1.Flex` free tier, 4 OCPU / 24 GB)
2. Install Docker + Docker Compose
3. Clone repo, `docker compose up -d`
4. Open ports: 8080 (API), 3000 (Grafana), 9090 (Prometheus)
5. Point a domain/subdomain via Cloudflare DNS → instance public IP
6. Cloudflare proxies with SSL termination

### 9.4 Cloudflare Frontend
- Cloudflare Pages for static UI (if built)
- Cloudflare DNS for API domain
- Cloudflare proxy provides: SSL termination, DDoS protection, caching of GET endpoints

---

## Phase 10 — Reconciliation & Safety Nets

### 10.1 Periodic Reconciliation Job (`@Scheduled(fixedDelay = 60000)`)
```
Every 60 seconds:
  1. For each active show:
     a. Count seats by status in Postgres
     b. Count seat keys in Redis (SCAN pattern seat:{show_id}:*)
     c. Compare: if Redis has keys that Postgres doesn't have as confirmed → stale hold, clean up
     d. If Postgres has confirmed seats that Redis doesn't → Redis lost state, re-populate
     e. Assert: available + held + confirmed == total_seats
     f. If drift detected: log WARN, set reconciliation_drift gauge, alert
```

### 10.2 Startup Reconciliation
- On app boot, warm Redis from Postgres state (in case Redis was restarted)
- Load all confirmed/held seats into Redis keys
- Rebuild user_count keys
- Rebuild hold_expiry ZSET for active holds

---

## Implementation Order (Suggested)

| Step | What | Est. Time |
|---|---|---|
| **1** | Project scaffold + Docker Compose + Flyway migrations | 2 hrs |
| **2** | Auth filter (token → user_id) + user seeding | 1.5 hrs |
| **3** | `POST /shows` (create show, seed seats) | 1 hr |
| **4** | Redis Lua script (`reserve_seats.lua`) — THE core | 3 hrs |
| **5** | `POST /shows/{id}/reserve` (calls Lua, returns result) | 2 hrs |
| **6** | `GET /shows/{id}` (seat map + reconciliation check) | 1 hr |
| **7** | `POST /reservations/{id}/cancel` | 1.5 hrs |
| **8** | `GET /reservations/{id}` | 30 min |
| **9** | Kafka producer + consumer (write-behind to Postgres) | 3 hrs |
| **10** | Background sweeper (ZSET expiry) | 1.5 hrs |
| **11** | Health endpoints (liveness + readiness) | 30 min |
| **12** | Prometheus metrics + Grafana dashboards | 2 hrs |
| **13** | Structured logging + request-id filter | 1 hr |
| **14** | SSE live state (Pub/Sub → SSE) | 2 hrs |
| **15** | Burst test script (Go program) | 2 hrs |
| **16** | HAProxy config + multi-instance testing | 1 hr |
| **17** | Reconciliation job + startup warmup | 1.5 hrs |
| **18** | Deployment to compute instance | 2 hrs |
| **19** | End-to-end burst test on live + fix edge cases | 2 hrs |
| **20** | WRITEUP.md | 1.5 hrs |
| | **Total** | **~30 hrs** |

---

## Key Design Decisions to Confirm

> [!IMPORTANT]
> Please confirm or adjust these decisions before I start coding:

### Decision 1: All-or-Nothing vs Best-Effort for Multi-Seat
**My recommendation**: **All-or-nothing**. If `["A12", "A13"]` is requested and A12 is taken, the entire request fails with 409. This is simpler to reason about under concurrency and is what the Lua script naturally supports.

### Decision 2: Hold-then-Confirm vs Instant-Confirm
**Your diagram shows**: A hold model with ZSET expiry (hold TTL, then auto-expire).
**The problem statement expects**: `"status": "confirmed"` in the 201 response.

**My recommendation**: **Instant-confirm** — the Redis Lua script claims the seat and it's immediately `confirmed`. Cancellation is explicit via `POST /reservations/{id}/cancel`. The ZSET expiry mechanism still exists as a safety net (e.g., 15-minute auto-cancel if user doesn't complete payment in a future payment flow). This satisfies the problem statement while keeping your expiry architecture for future use.

### Decision 3: Burst Script Language
**Options**: Go (true parallelism, best for 20k concurrent), Python (`asyncio` + `aiohttp`), or shell (`hey`/`wrk`).
**My recommendation**: **Go** — real goroutine concurrency, easy to compile, no runtime deps.

### Decision 4: Gradle vs Maven
**My recommendation**: **Gradle** (Kotlin DSL) — faster builds, cleaner config, better for Docker layer caching.

### Decision 5: Single App with Consumer Thread vs Separate Consumer Service
**My recommendation**: **Single app** with `@KafkaListener` — simpler deployment, fewer containers, still works correctly. The consumer runs in its own thread pool. Separate service only if you want independent scaling.

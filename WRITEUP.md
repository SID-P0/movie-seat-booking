The core challenge: 20k people hit "Book A12" at the same millisecond. Exactly one wins. No two.

## 1. The Atomic Decision
The mechanism is a **Redis Lua script** (`reserve_seats.lua`). Redis is single-threaded, and Lua scripts are atomic — nothing interrupts them mid-execution. Inside one uninterruptible run, the script does:
1. **Idempotency check** — `GET idemp:{key}` first. If the key exists, we've seen this request before. Return the original result immediately, no side effects.
2. **Per-user limit check** — `GET user_count:{show}:{user}`. If adding the requested seats would breach the per-show limit, decline immediately.
3. **Seat claim** — `SET seat:{show}:{seatId} {reservationId} NX EX {ttl}`. The `NX` flag means "set only if this key does not exist." This is the atomic moment. 500 concurrent threads all fire this at Redis — Redis serializes them. Exactly one gets `OK`. The other 499 get `nil`. No gap between read and write. The structure itself is the lock.
4. **All-or-nothing on multi-seat** — if any seat in a batch fails the `NX` check, we immediately `DEL` every seat we already claimed in this run and return `SEAT_TAKEN`. Nobody ends up with a partial booking.

**Why it's race-free**: There is no read-then-write gap. The `SET NX` IS the read AND the write in one atomic command. 500 users hit `SET seat:show:A12 NX` — Redis processes them sequentially. Exactly one succeeds (returns `OK`); the other 499 get `nil`. No locks, no retries, no optimistic concurrency — the data structure itself prevents the race.

**Multi-seat deadlock avoidance**: Seats are sorted alphabetically before claiming. This deterministic ordering prevents ABBA deadlock patterns where User1 tries [A12, A13] while User2 tries [A13, A12].

## 2. Idempotency

**Where the key is stored**:
- **Fast path (Redis)**: The Lua script does `SET idemp:{key} {reservationId} NX EX {ttl}` inside the same atomic block as the seat claim. If the key already exists, we skip everything and return the original result.
- **Durable path (Postgres)**: `reservations.idempotency_key` has a `UNIQUE` constraint. Even if Redis is flushed or Kafka replays the message, the Postgres insert fails gracefully on conflict.

**How exactly-once is enforced**:
1. The Lua script checks `GET idemp:{key}` before any seat claim
2. If the key exists, it returns `IDEMPOTENT_HIT` with the original `reservation_id`
3. The API layer then fetches the original reservation and returns it
4. In Postgres, the `UNIQUE` constraint prevents duplicate inserts even if Kafka replays the message

**Same-key-different-body handling**:
When an idempotent hit occurs, the API compares the requested seats with the original reservation's seats. If they differ → 409 Conflict with `idempotency_conflict` error. This prevents a client from accidentally reusing a key for a different booking.

## 3. Holds & Expiry

**Model**: Instant-confirm with time-boxed safety net

When a reservation is created, the seat keys in Redis get a TTL (15 seconds in demo, typically 10 minutes in production). The user must confirm before that expires.


**Expiry mechanism**:
- Redis `ZSET` (`hold_expiry`) stores members as `show_id:seat_id:reservation_id` with score = expiry epoch in ms
- A **Background Sweeper** (`@Scheduled(fixedDelay = 1000)`) polls `ZRANGEBYSCORE hold_expiry 0 {now}` every second
- For expired entries: verifies the seat key still matches the reservation, `DEL`s the key, produces a Kafka expiry event
- The consumer updates Postgres (sets reservation status to `expired`, releases the seat)

**Multi-instance safety**: The sweeper acquires a Redis leader lock (`SET sweeper_lock NX EX 2`) — only one instance sweeps at a time.

## 4. Consistency vs Availability Under a Partition

**Redis down**: The system **fails closed**. The readiness endpoint returns 503, HAProxy stops routing traffic. No reservations can be made without Redis (it's the gatekeeper). This is the correct choice as we would be accepting requests without the atomic decision layer

**Kafka goes down**: Degrade gracefully. Redis claims still work. Reservations succeed. Postgres just doesn't hear about it yet. When Kafka recovers, `RedisStateSyncService` rehydrates any drift. On startup, it reconciles Redis state from Postgres to close gaps from crashes.

**Postgres down**: The system **degrades**. New reservations can still be created (Redis-first). But `GET /reservations/{id}` and cancellations that rely on Postgres data will fail. The readiness probe reports unhealthy

**Design philosophy**: We prioritize correctness over availability. It's better to refuse requests (503) than to risk a double-sell.

**Startup initialization**: In a scenario where redis went down for a few seconds would require the current hold state from db and populate the redis cache accordingly. The sweeper would then be able to pick up expired holds.

**Reducing Load from db**: As db is acting as our source of truth with minimal hikariCPconnections we prefil our entire shows data in mem-mapping and into the redis cache at startup.

## 5. Observability 

**Critical alerts (PagerDuty)**:
1. **`seats_available` gauge drops below 0 for any show** — reconciliation invariant violated, possible double-sell
2. **5xx error rate > 0 sustained for 30s** — infrastructure failure bleeding to users
3. **`kafka_produce_failures_total` increasing > 10/min** — Kafka partition down, write-behind broken
4. **Readiness probe returns 503** — Redis or Postgres down

**Warning alerts (Slack)**:
1. Redis Lua script p99 latency > 50ms — contention or Redis degradation
2. Kafka consumer lag > 1000 — consumer falling behind
3. HAProxy backend down — one API instance unhealthy

Live dashboards at `http://34.14.203.181:3000` — Grafana shows HTTP 2xx/4xx/5xx rates, confirmed vs declined breakdown by reason (seat taken / user limit / idempotent replay), seat gauges, Redis Lua latency, Kafka health, and sweeper activity. Run the burst test and watch everything in real time.

## 5.1 Scenario's tested:

1. Atomic Reservation (Single & Multi-seat): Verified that only one user can reserve the same seat(s) under high concurrency. Confirmed that seats are reserved atomically as a single unit (all or nothing) and held seats are released after expiry.
2. Idempotency: Verified that duplicate requests (using the same idempotency key) are ignored after the first successful reservation. Also tested that conflicting requests (same key, different seats) are rejected with a conflict error.
3. Race Conditions: Verified that concurrent reservation attempts by different users on the same seat are handled safely, with only one user succeeding and others receiving appropriate error responses.
4. Postgres Failure: Simulated Postgres downtime by stopping the Postgres container. Verified that the system degrades gracefully (reservations still work), and the database is reconciled on recovery.
5. Backend Unavailability: Simulated API instance failure. Verified that HAProxy stops routing traffic to the unhealthy instance, and the system remains available through healthy instances.
6. High Concurrency (20k users): Verified the system handles 20k concurrent users requesting reservations without errors. Confirmed that the reservation logic holds up under load.
7. Seat Limit Enforcement: Verified that the per-user seat limit (default: 4 seats) is strictly enforced, preventing users from exceeding their allocation.
8. Observability: Verified that all alerts (seat count, 5xx errors, Kafka lag, etc.) are triggered correctly and displayed in Grafana. Verified that dashboards show real-time metrics as expected.
9. SSE events populating correctly in real time during burst, UI handling the current state correctly.
10. Cancelling seats and ensuring seat is available for other users, available in real time for second user.


## 6. AI Usage

**Directed (I specified what to build, AI helped write it)**:
- Project scaffolding (Gradle multi-module setup, Spring Boot boilerplate)
- Docker Compose and Dockerfile generation
- Exception hierarchy and GlobalExceptionHandler
- HAProxy, Prometheus, and Grafana configuration
- Burst test shell script generation

**Decided (AI made design calls, I reviewed)**:
- Specific Lua script structure and ARGV/KEYS layout
- The `fetchExistingReservation` flow for idempotent hits where Kafka hasn't consumed yet
- Consumer's `ON CONFLICT` handling approach
-`@JdbcTypeCode(SqlTypes.ARRAY)`
- Grafana dashboard panels silently gone — malformed JSON (missing `}`)
- `crypto.randomUUID()` crashing on HTTP origins — replaced with Math.random polyfill
- CORS preflight OPTIONS getting 401 from AuthTokenFilter before CORS headers could fire

**Genuinely mine (from the architecture diagram)**:
- The entire architecure diagram was propopsed by me.
- The decision to use Redis Lua as the atomic admission layer
- Kafka write-behind to protect Postgres from stampede
- ZSET-based hold expiry with background sweeper
- Redis Pub/Sub → SSE for live seat state
- The overall system architecture and component topology

## 7. What I'd Do Next

1. **Payment flow**: Add a two-phase hold→confirm model with actual payment integration. The ZSET expiry becomes the unpaid hold timeout.
2. **Redis Cluster**: For horizontal Redis scaling, use hash tags `{show_id}` in key names so all keys for a show land on the same shard.
3. **Kubernetes**: Replace Docker Compose with Helm charts for auto-scaling, rolling deploys, and health-based pod rotation.
4. **Rate limiting**: Per-user rate limiting at the HAProxy/Cloudflare layer to prevent DDoS.
5. **Waiting room**: At extreme scale (100k+ concurrent), add a virtual queue before the reservation endpoint.
6. **TLS everywhere**: mTLS between services, SSL for Redis and Postgres connections.
7. **Chaos testing**: Inject Redis failures, Kafka partitions, consumer crashes to validate the reconciliation safety net.

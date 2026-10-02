# WRITEUP.md — Seat Booking System Design & Decisions

## 1. The Atomic Decision

**Mechanism**: Redis Lua Script with `SET NX` (SET if Not eXists)

The atomic decision for seat claiming lives in a **single Redis Lua script** (`reserve_seats.lua`) that executes without interleaving — Redis is single-threaded and Lua scripts are atomic.

Within one uninterruptible execution, the script:
1. **Idempotency check** — `GET idemp:{key}` — if exists, return original reservation (no re-processing)
2. **Per-user limit check** — `GET user_count:{show_id}:{user_id}` — if `current + requested > limit`, decline
3. **Seat claim** — `SET seat:{show_id}:{seat_id} reservation_id NX EX ttl` — the conditional write. `NX` means "set only if the key does not exist." If it does, the seat is taken.
4. **All-or-nothing rollback** — if any seat in a multi-seat request fails the `SET NX`, we `DEL` all previously claimed seats and return `SEAT_TAKEN`

**Why it's race-free**: There is no read-then-write gap. The `SET NX` IS the read AND the write in one atomic command. 500 users hit `SET seat:show:A12 NX` — Redis processes them sequentially. Exactly one succeeds (returns `OK`); the other 499 get `nil`. No locks, no retries, no optimistic concurrency — the data structure itself prevents the race.

**Multi-seat deadlock avoidance**: Seats are sorted alphabetically before claiming. This deterministic ordering prevents ABBA deadlock patterns where User1 tries [A12, A13] while User2 tries [A13, A12].

## 2. Idempotency

**Where the key is stored**:
- **Fast path**: Redis key `idemp:{idempotency_key}` → value is the `reservation_id`, set with `NX` inside the Lua script
- **Durable backstop**: Postgres `reservations.idempotency_key` with a `UNIQUE` constraint

**How exactly-once is enforced**:
1. The Lua script checks `GET idemp:{key}` before any seat claim
2. If the key exists, it returns `IDEMPOTENT_HIT` with the original `reservation_id`
3. The API layer then fetches the original reservation and returns it
4. In Postgres, the `UNIQUE` constraint prevents duplicate inserts even if Kafka replays the message

**Same-key-different-body handling**:
When an idempotent hit occurs, the API compares the requested seats with the original reservation's seats. If they differ → 409 Conflict with `idempotency_conflict` error. This prevents a client from accidentally reusing a key for a different booking.

## 3. Holds & Expiry

**Model**: Instant-confirm with time-boxed safety net

Reservations are immediately `confirmed` on successful Redis claim (matching the problem statement). The Redis seat keys have a TTL (`HOLD_TTL_SECONDS`, default 900s = 15 minutes) as a safety mechanism.

**Expiry mechanism**:
- Redis `ZSET` (`hold_expiry`) stores members as `show_id:seat_id:reservation_id` with score = expiry epoch in ms
- A **Background Sweeper** (`@Scheduled(fixedDelay = 1000)`) polls `ZRANGEBYSCORE hold_expiry 0 {now}` every second
- For expired entries: verifies the seat key still matches the reservation, `DEL`s the key, produces a Kafka expiry event
- The consumer updates Postgres (sets reservation status to `expired`, releases the seat)

**Multi-instance safety**: The sweeper acquires a Redis leader lock (`SET sweeper_lock NX EX 2`) — only one instance sweeps at a time.

## 4. Consistency vs Availability Under a Partition

**Redis down**: The system **fails closed**. The readiness endpoint returns 503, HAProxy stops routing traffic. No reservations can be made without Redis (it's the gatekeeper). This is the correct choice as we would be accepting requests without the atomic decision layer

**Kafka down**: The system **degrades gracefully**. Reservations still succeed (Redis claim is the source of truth for admission). Kafka produce failures are logged and metered. The data eventually reaches Postgres when Kafka recovers, or is caught by the periodic reconciliation job.

**Postgre.s down**: The system **degrades**. New reservations can still be created (Redis-first). But `GET /reservations/{id}` and cancellations that rely on Postgres data will fail. The readiness probe reports unhealthy

**Design philosophy**: We prioritize correctness over availability. It's better to refuse requests (503) than to risk a double-sell.

## 5. Observability 

**Critical alerts (PagerDuty)**:
1. **`seats_available` gauge drops below 0 for any show** — reconciliation invariant violated, possible double-sell
2. **5xx error rate > 0 sustained for 30s** — infrastructure failure bleeding to users
3. **`kafka_produce_failures_total` increasing > 10/min** — Kafka partition down, write-behind broken
4. **Readiness probe returns 503** — Redis or Postgres down
5. **`reconciliation_drift` gauge != 0** — Redis and Postgres state have diverged

**Warning alerts (Slack)**:
1. Redis Lua script p99 latency > 50ms — contention or Redis degradation
2. Kafka consumer lag > 1000 — consumer falling behind
3. HAProxy backend down — one API instance unhealthy

**Dashboards**: Pre-provisioned Grafana dashboard with burst metrics (confirmed vs declined rate), seat gauges, latency percentiles, Kafka health, and sweeper activity.

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

**Genuinely mine (from the architecture diagram)**:
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

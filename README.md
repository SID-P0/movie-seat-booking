# 🎬 Movie Seat Booking API

A highly concurrent, linearly scalable seat booking API designed to prevent race conditions and handle massive traffic spikes (on-sale stampedes) with zero double-bookings.

---

## 1. Installation & Setup (One-Shot)

The entire stack (APIs, PostgreSQL, Redis, Kafka, Consumer, HAProxy, Prometheus, Grafana) can be spun up with a single command. 

**Prerequisites:**
- Java 21 & Gradle 8.14.3 (for building the JARs)
- Docker & Docker Compose

**Build & Run:**
```bash
# 1. Build the Java JARs
./gradlew clean build -x test

# 2. Boot the infrastructure & services
docker compose up -d --build
```

**What happens on `docker compose up`?**
- **Infrastructure Boots**: Postgres, Redis, and Kafka start up.
- **Init Scripts Run**: `init-db` runs Flyway SQL migrations. `init-kafka` creates the `reservations` and `seat-expirations` topics.
- **Services Boot**: `api` (scaled to 2 replicas by default) and `consumer` start up.
- **Routing**: HAProxy dynamically discovers the `api` replicas and load-balances traffic across them.

---

## 📊 2. Ports & Monitoring Links

Once the stack is healthy, you can access the following services:

| Service | URL | Credentials (if any) |
|---------|-----|----------------------|
| **API Entrypoint (HAProxy)** | `http://localhost:8080` | N/A |
| **Grafana Dashboard** | [http://localhost:3000](http://localhost:3000/d/seat-booking-burst/seat-booking-burst-dashboard) | `admin` / `admin` |
| **Prometheus Metrics** | [http://localhost:9090](http://localhost:9090) | N/A |
| **HAProxy Stats** | `http://localhost:8404/stats` | N/A |

*Note: You can scale the API to any number of nodes dynamically! HAProxy and Prometheus will auto-discover them.*
```bash
docker compose up -d --scale api=5
```

---

## ⚡ 3. Performing API Actions

All API requests should be sent to the HAProxy load balancer at `http://localhost:8080`.

### A. Health & Liveness
```bash
curl http://localhost:8080/health/ready
```

### B. Register a User
Create a user to get an authentication token.
```bash
curl -X POST http://localhost:8080/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "sidhesh.vaity@gmail.com"
  }'
```
*Returns `user_id` and the `token`. Keep the token!*

### C. Create a Show (Admin Only)
*Requires the Admin Token.*
```bash
curl -X POST http://localhost:8080/shows \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer super-secret-admin-token" \
  -d '{
    "name": "Avengers: Secret Wars",
    "totalSeats": 100,
    "price": 25000,
    "perUserLimit": 4
  }'
```
*Returns the created show with an ID and every seat in the "available" state.*

### D. Show State (Seat Map & Counts)
```bash
curl http://localhost:8080/shows/<SHOW_ID>
```
*Returns per-seat status (available / held / confirmed) and counts.*

### E. Reserve Seats (Authenticated User)
Reserve seats atomically using an Idempotency-Key. The user identity comes entirely from the Authorization token!
```bash
curl -X POST http://localhost:8080/shows/<SHOW_ID>/reserve \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <USER_TOKEN>" \
  -d '{
    "seats": ["A1", "A2"],
    "idempotencyKey": "<UNIQUE_UUID>"
  }'
```
*Behavior guarantees: No double-sell (409 on conflict), respects per-user limits, perfectly idempotent, and all-or-nothing partial requests.*

### F. Cancel / Release Reservation
Cancel a reservation (must be the owner).
```bash
curl -X POST http://localhost:8080/reservations/<RESERVATION_ID>/cancel \
  -H "Authorization: Bearer <USER_TOKEN>"
```
*A released seat instantly becomes cleanly re-bookable by anyone else.*

---

## 💥 5. Running the Burst Test

To simulate an on-sale stampede (5,000 requests hitting the API concurrently for 100 seats), run the provided `burst.sh` script:

```bash
chmod +x burst.sh
./burst.sh http://localhost:8080
```
*Open the **Grafana Dashboard** while running this script to watch the system elegantly handle the load, reject conflicts, and avoid double-sells in real time!*
# 🎬 Movie Seat Booking API

A highly concurrent, linearly scalable seat booking API designed to prevent race conditions and handle massive traffic spikes (on-sale stampedes) with zero double-bookings.

---

![alt text](image.png)

## 1. Local Setup & Execution

The entire stack (APIs, PostgreSQL, Redis, Kafka, Consumer, HAProxy, Prometheus, Grafana, Dozzle) can be spun up with a single command. The infrastructure strictly manages startup ordering so there are no race conditions during initialization.

**Prerequisites:**

- Java 21 & Gradle 8.14.3
- Docker & Docker Compose

- sudo apt-get update
- sudo apt-get install -y openjdk-21-jdk

**Step-by-Step Setup:**

```bash
# 1. Chmod
chmod +x gradlew
chmod +x burst.sh

# 2. Build the Java JARs
./gradlew clean build -x test

# 3. Boot the infrastructure & services
docker compose up -d --build
```

**What happens on `docker compose up`?**

1. **Infrastructure Boots**: Postgres, Redis, and Kafka start up.
2. **Init Scripts Run**: `init-db` runs Flyway SQL migrations. `init-kafka` creates the exactly required `reservations` and `seat-expirations` topics.
3. **Services Boot**: The Spring Boot `api` nodes and `consumer` wait until init scripts succeed, then they start.
4. **Routing & Metrics**: HAProxy dynamically routes to API replicas. Prometheus and Grafana begin scraping.

---

## 📊 2. Ports & Monitoring Links

Once the stack is healthy, you can access the following services:

| Service                      | URL                                                                                                              | Credentials (if any) |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------- | -------------------- |
| **UI (Nginx)**               | [http://34.14.203.181](http://34.14.203.181)                                                                     | N/A                  |
| **API Entrypoint (HAProxy)** | [http://34.14.203.181:8080](http://34.14.203.181:8080)                                                           | N/A                  |
| **Swagger UI**               | [http://34.14.203.181:8080/swagger-ui/index.html](http://34.14.203.181:8080/swagger-ui/index.html)               | N/A                  |
| **Dozzle (Real-time Logs)**  | [http://34.14.203.181:8081](http://34.14.203.181:8081)                                                           | N/A                  |
| **Grafana Dashboard**        | [http://34.14.203.181:3000](http://34.14.203.181:3000/d/seat-booking-burst/seat-booking-burst-dashboard)         | `admin` / `admin`    |
| **Prometheus Metrics**       | [http://34.14.203.181:9090](http://34.14.203.181:9090)                                                           | N/A                  |
| **HAProxy Stats**            | [http://34.14.203.181:8404/stats](http://34.14.203.181:8404/stats)                                               | N/A                  |

_Note: You can easily scale the API to handle more load. HAProxy will auto-discover the new nodes:_

```bash
docker compose up -d --scale api=5
```

---

## 💥 3. Running the Burst Test

To simulate an on-sale stampede (e.g. 20,000 requests hitting the API concurrently), run the provided `burst.sh` script:

```bash
chmod +x burst.sh
./burst.sh http://localhost:8080
```

_Open the **Grafana Dashboard** and **Dozzle Logs** while running this script to watch the system elegantly handle the massive concurrency, instantly reject conflicts via Redis Lua, and process Kafka events without a single double-sell!_

---

## ⚡ 4. Performing API Actions

All API requests should be sent to the HAProxy load balancer at `http://localhost:8080`.

### A. Health & Liveness

```bash
curl http://localhost:8080/health/live
```

### B. Register a User

Create a user to get an authentication token.

```bash
curl -X POST http://localhost:8080/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com"
  }'
```

_Returns `userId` and `token`. Keep the token!_

### C. Create a Show (Admin Only)

_Requires the Admin Token._

```bash
curl -X POST http://localhost:8080/shows \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer super-secret-admin-token" \
  -d '{
    "name": "Avengers: Secret Wars",
    "seats": ["A1", "A2", "A3", "B1", "B2", "B3"],
    "price": 25000
  }'
```

_Returns the created show with a `showId` and the generated seat map._

### D. Get Show State (Seat Map)

```bash
curl http://localhost:8080/shows/<SHOW_ID>
```

_Returns per-seat status (available / held / confirmed) and active counts._

### E. Reserve Seats

Reserve seats atomically. The identity comes purely from the provided authentication token.

```bash
curl -X POST http://localhost:8080/shows/<SHOW_ID>/reserve \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <USER_TOKEN>" \
  -d '{
    "seats": ["A1", "A2"],
    "idempotencyKey": "<UNIQUE_UUID>"
  }'
```

_Behavior guarantees: No double-sell (409 on conflict), 15-second TTL enforced by Redis, and perfectly idempotent._

### F. Confirm Reservation

Confirm a held reservation (must be the owner).

```bash
curl -X POST http://localhost:8080/reservations/<RESERVATION_ID>/confirm \
  -H "Authorization: Bearer <USER_TOKEN>"
```

### G. Cancel / Release Reservation

Cancel a reservation (must be the owner).

```bash
curl -X POST http://localhost:8080/reservations/<RESERVATION_ID>/cancel \
  -H "Authorization: Bearer <USER_TOKEN>"
```

_A released seat instantly becomes cleanly re-bookable by anyone else._

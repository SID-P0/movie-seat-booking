package com.seatbooking.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * Health endpoints for liveness and readiness probes.
 *
 * - Liveness: Is the JVM alive? (always 200 unless process is wedged)
 * - Readiness: Can we serve traffic? Checks Redis PING + Postgres SELECT 1.
 *   Returns 503 if any dependency is down (fail closed).
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    @GetMapping("/health/live")
    public ResponseEntity<Map<String, String>> liveness() {
        return ResponseEntity.ok(Map.of("status", "alive"));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        boolean postgresOk = checkPostgres();
        boolean redisOk = checkRedis();
        boolean ready = postgresOk && redisOk;

        Map<String, Object> body = Map.of(
                "status", ready ? "ready" : "not_ready",
                "postgres", postgresOk ? "ok" : "down",
                "redis", redisOk ? "ok" : "down"
        );

        if (ready) {
            return ResponseEntity.ok(body);
        } else {
            return ResponseEntity.status(503).body(body);
        }
    }

    private boolean checkPostgres() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkRedis() {
        try {
            var connection = redisConnectionFactory.getConnection();
            String pong = connection.ping();
            connection.close();
            return "PONG".equals(pong);
        } catch (Exception e) {
            return false;
        }
    }
}

package com.seatbooking.api.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The atomic admission layer — the heart of the system.
 *
 * Executes Redis Lua scripts that atomically:
 * 1. Check idempotency (NX on idemp key)
 * 2. Check per-user limit
 * 3. Claim seats (SET NX — the conditional write)
 * 4. All-or-nothing rollback on any failure
 *
 * Because Redis is single-threaded and Lua scripts execute atomically,
 * there is no interleaving. No read-then-write race is possible.
 */
@Service
@Slf4j
public class RedisAdmissionService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> reserveScript;
    private final DefaultRedisScript<List> cancelScript;
    private final Timer luaDurationTimer;

    @Value("${app.hold-ttl-seconds:900}")
    private int holdTtlSeconds;

    private static final String HOLD_EXPIRY_ZSET = "hold_expiry";

    public RedisAdmissionService(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("reserveSeatsScript") DefaultRedisScript<List> reserveScript,
            @Qualifier("cancelSeatsScript") DefaultRedisScript<List> cancelScript,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.reserveScript = reserveScript;
        this.cancelScript = cancelScript;
        this.luaDurationTimer = Timer.builder("redis.lua.duration")
                .description("Duration of Redis Lua script execution")
                .register(meterRegistry);
    }

    /**
     * Result of the atomic admission attempt.
     */
    public record AdmissionResult(
            Status status,
            String reservationId,
            String detail
    ) {
        public enum Status {
            OK,
            IDEMPOTENT_HIT,
            SEAT_TAKEN,
            USER_LIMIT_EXCEEDED
        }
    }

    /**
     * Attempt to reserve seats atomically.
     *
     * @param showId          the show
     * @param userId          derived from auth token
     * @param seatIds         requested seats
     * @param idempotencyKey  client-provided idempotency key
     * @param perUserLimit    max seats per user per show
     * @return the admission result
     */
    public AdmissionResult attemptReservation(
            UUID showId, UUID userId, List<String> seatIds,
            String idempotencyKey, int perUserLimit) {

        String reservationId = UUID.randomUUID().toString();
        int numSeats = seatIds.size();

        // Build KEYS array
        // KEYS[1] = idemp:{idempotencyKey}
        // KEYS[2] = user_count:{showId}:{userId}
        // KEYS[3..] = seat:{showId}:{seatId}
        List<String> keys = new ArrayList<>();
        keys.add("idemp:" + idempotencyKey);
        keys.add("user_count:" + showId + ":" + userId);
        for (String seatId : seatIds) {
            keys.add("seat:" + showId + ":" + seatId);
        }

        // Build ARGV array
        long expiryEpochMs = Instant.now().plusSeconds(holdTtlSeconds).toEpochMilli();
        List<String> args = new ArrayList<>();
        args.add(reservationId);                        // ARGV[1]
        args.add(userId.toString());                     // ARGV[2]
        args.add(String.valueOf(perUserLimit));           // ARGV[3]
        args.add(String.valueOf(numSeats));               // ARGV[4]
        args.add(String.valueOf(holdTtlSeconds));         // ARGV[5]
        args.add(HOLD_EXPIRY_ZSET);                      // ARGV[6]
        args.add(String.valueOf(expiryEpochMs));          // ARGV[7]
        for (String seatId : seatIds) {
            args.add(seatId);                             // ARGV[8..8+numSeats-1] = seat_ids
        }
        args.add(showId.toString());                      // ARGV[8+numSeats] = show_id

        // Execute the Lua script atomically
        List<?> result = luaDurationTimer.record(() ->
                redisTemplate.execute(reserveScript, keys, args.toArray(new String[0]))
        );

        if (result == null || result.isEmpty()) {
            log.error("Redis Lua script returned null/empty result for show={} user={}", showId, userId);
            return new AdmissionResult(AdmissionResult.Status.SEAT_TAKEN, null, "internal_error");
        }

        String status = result.get(0).toString();
        String detail = result.size() > 1 ? result.get(1).toString() : "";

        return switch (status) {
            case "OK" -> {
                log.info("Seat reservation OK: reservationId={} show={} user={} seats={}",
                        reservationId, showId, userId, seatIds);
                yield new AdmissionResult(AdmissionResult.Status.OK, reservationId, null);
            }
            case "IDEMPOTENT_HIT" -> {
                log.info("Idempotent replay: existingReservation={} key={}", detail, idempotencyKey);
                yield new AdmissionResult(AdmissionResult.Status.IDEMPOTENT_HIT, detail, null);
            }
            case "SEAT_TAKEN" -> {
                log.info("Seat taken: seat={} show={} user={}", detail, showId, userId);
                yield new AdmissionResult(AdmissionResult.Status.SEAT_TAKEN, null, detail);
            }
            case "USER_LIMIT_EXCEEDED" -> {
                log.info("User limit exceeded: currentCount={} show={} user={}", detail, showId, userId);
                yield new AdmissionResult(AdmissionResult.Status.USER_LIMIT_EXCEEDED, null, detail);
            }
            default -> {
                log.error("Unknown Lua result status: {}", status);
                yield new AdmissionResult(AdmissionResult.Status.SEAT_TAKEN, null, "unknown_error");
            }
        };
    }

    /**
     * Cancel/release seats atomically.
     */
    public boolean cancelReservation(
            UUID showId, UUID userId, String reservationId,
            List<String> seatIds, String idempotencyKey) {

        int numSeats = seatIds.size();

        List<String> keys = new ArrayList<>();
        keys.add("idemp:" + idempotencyKey);
        keys.add("user_count:" + showId + ":" + userId);
        for (String seatId : seatIds) {
            keys.add("seat:" + showId + ":" + seatId);
        }

        List<String> args = new ArrayList<>();
        args.add(reservationId);                        // ARGV[1]
        args.add(String.valueOf(numSeats));               // ARGV[2]
        args.add(HOLD_EXPIRY_ZSET);                      // ARGV[3]
        for (String seatId : seatIds) {
            // ZSET member = show_id:seat_id:reservation_id
            args.add(showId + ":" + seatId + ":" + reservationId);
        }

        List<?> result = redisTemplate.execute(cancelScript, keys, (Object[]) args.toArray(new String[0]));

        if (result != null && !result.isEmpty() && "OK".equals(result.get(0).toString())) {
            log.info("Reservation cancelled in Redis: reservationId={} released={}",
                    reservationId, result.get(1));
            return true;
        }

        log.warn("Cancel failed in Redis: reservationId={}", reservationId);
        return false;
    }

    /**
     * Confirm a held reservation (removes from expiry ZSET and persists keys).
     */
    public boolean confirmReservation(
            UUID showId, UUID userId, String reservationId,
            List<String> seatIds, String idempotencyKey) {

        // Remove from hold expiry ZSET
        for (String seatId : seatIds) {
            redisTemplate.opsForZSet().remove(HOLD_EXPIRY_ZSET, showId + ":" + seatId + ":" + reservationId);
        }

        // Persist the seat keys so they don't expire
        for (String seatId : seatIds) {
            redisTemplate.persist("seat:" + showId + ":" + seatId);
        }

        // Persist the idempotency key and user count key as well
        redisTemplate.persist("idemp:" + idempotencyKey);
        redisTemplate.persist("user_count:" + showId + ":" + userId);

        return true;
    }
}

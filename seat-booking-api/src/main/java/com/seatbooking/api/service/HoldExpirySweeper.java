package com.seatbooking.api.service;

import com.seatbooking.common.event.ReservationEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Background sweeper that polls the Redis ZSET for expired holds.
 * Runs every 1 second, processes up to 100 expired entries per tick.
 *
 * For multi-instance deployment, uses a Redis-based leader lock
 * so only one instance runs the sweep at a time.
 */
@Service
@Slf4j
public class HoldExpirySweeper {

    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, ReservationEvent> kafkaTemplate;
    private final Counter expiredCounter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String HOLD_EXPIRY_ZSET = "hold_expiry";
    private static final String SWEEPER_LOCK = "sweeper_lock";
    private static final String EXPIRATIONS_TOPIC = "seat-expirations";

    @Value("${app.sweeper.batch-size:100}")
    private int batchSize;

    public HoldExpirySweeper(
            RedisTemplate<String, String> redisTemplate,
            KafkaTemplate<String, ReservationEvent> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.expiredCounter = Counter.builder("seats.expired.total")
                .description("Total seats expired by sweeper")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 1000)
    public void sweep() {
        // Acquire leader lock (2s TTL) — only one instance sweeps at a time
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(SWEEPER_LOCK, "locked", java.time.Duration.ofSeconds(2));

        if (acquired == null || !acquired) {
            return; // Another instance is sweeping
        }

        try {
            long now = Instant.now().toEpochMilli();

            // Get expired entries: score (expiry time) <= now
            Set<ZSetOperations.TypedTuple<String>> expired = redisTemplate.opsForZSet()
                    .rangeByScoreWithScores(HOLD_EXPIRY_ZSET, 0, now, 0, batchSize);

            if (expired == null || expired.isEmpty()) {
                return;
            }

            for (ZSetOperations.TypedTuple<String> entry : expired) {
                String member = entry.getValue();
                if (member == null) continue;

                // Member format: show_id:seat_id:reservation_id
                String[] parts = member.split(":", 3);
                if (parts.length != 3) {
                    log.warn("Invalid ZSET member format: {}", member);
                    redisTemplate.opsForZSet().remove(HOLD_EXPIRY_ZSET, member);
                    continue;
                }

                String showId = parts[0];
                String seatId = parts[1];
                String reservationId = parts[2];

                // Check if the seat key still belongs to this reservation
                String seatKey = "seat:" + showId + ":" + seatId;
                String currentHolder = redisTemplate.opsForValue().get(seatKey);

                if (currentHolder == null || reservationId.equals(currentHolder)) {
                    // Expire it: delete the seat key (if it still exists)
                    if (currentHolder != null) {
                        redisTemplate.delete(seatKey);
                    }

                    // Decrement user count (we'd need user_id — stored in a separate key or skip)
                    // For safety, the consumer will handle the Postgres update

                    expiredCounter.increment();
                    log.info("Seat expired: show={} seat={} reservation={}", showId, seatId, reservationId);

                    // Produce expiration event to Kafka
                    ReservationEvent event = ReservationEvent.builder()
                            .reservationId(reservationId)
                            .showId(showId)
                            .seats(List.of(seatId))
                            .status("expired")
                            .eventType(ReservationEvent.EventType.EXPIRED)
                            .timestamp(Instant.now())
                            .build();

                    kafkaTemplate.send(EXPIRATIONS_TOPIC, showId, event);
                    
                    // Publish SSE so UI updates
                    try {
                        String ssePayload = objectMapper.writeValueAsString(
                                java.util.Map.of("seatId", seatId, "status", "available"));
                        redisTemplate.convertAndSend("show_state:" + showId, ssePayload);
                    } catch (Exception ex) {
                        log.error("Failed to publish SSE for expired seat {}", seatId, ex);
                    }
                }

                // Remove from ZSET regardless
                redisTemplate.opsForZSet().remove(HOLD_EXPIRY_ZSET, member);
            }
        } catch (Exception e) {
            log.error("Sweeper error: {}", e.getMessage(), e);
        }
    }
}

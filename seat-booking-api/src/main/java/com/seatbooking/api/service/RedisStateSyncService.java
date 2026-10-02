package com.seatbooking.api.service;

import com.seatbooking.api.repository.ReservationRepository;
import com.seatbooking.common.entity.ReservationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rehydrates Redis from Postgres on application startup.
 * Ensures that if Redis crashes or restarts (and loses data due to noeviction/no-persistence),
 * the API reconstructs the active state (held & confirmed seats, idempotency keys, and hold expiry ZSET).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStateSyncService implements ApplicationRunner {

    private final ReservationRepository reservationRepository;
    private final RedisTemplate<String, String> redisTemplate;
    
    private static final String HOLD_EXPIRY_ZSET = "hold_expiry";

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting Redis state synchronization from Postgres...");
        
        List<ReservationEntity> activeReservations = reservationRepository.findAllActive();
        
        if (activeReservations.isEmpty()) {
            log.info("No active reservations found. Redis is clean.");
            return;
        }

        Map<String, Integer> userCounts = new HashMap<>();

        for (ReservationEntity res : activeReservations) {
            String showId = res.getShowId().toString();
            String userId = res.getUserId().toString();
            String reservationId = res.getReservationId().toString();
            String idempotencyKey = res.getIdempotencyKey();
            
            // 1. Restore seat keys
            if (res.getSeats() != null) {
                for (String seatId : res.getSeats()) {
                    String seatKey = "seat:" + showId + ":" + seatId;
                    redisTemplate.opsForValue().set(seatKey, reservationId);
                    
                    // If held, restore ZSET for sweeper
                    if ("held".equals(res.getStatus()) && res.getExpiresAt() != null) {
                        String zsetMember = showId + ":" + seatId + ":" + reservationId;
                        long expiresAtMs = res.getExpiresAt().toEpochMilli();
                        
                        // Only add to expiry if it hasn't already expired
                        if (expiresAtMs > Instant.now().toEpochMilli()) {
                            redisTemplate.opsForZSet().add(HOLD_EXPIRY_ZSET, zsetMember, expiresAtMs);
                        } else {
                            // Expired while Redis was down, add it with current time so sweeper immediately cleans it
                            redisTemplate.opsForZSet().add(HOLD_EXPIRY_ZSET, zsetMember, Instant.now().toEpochMilli());
                        }
                    }
                }
                
                // 2. Track user counts
                String countKey = "user_count:" + showId + ":" + userId;
                userCounts.put(countKey, userCounts.getOrDefault(countKey, 0) + res.getSeats().size());
            }
            
            // 3. Restore idempotency key
            if (idempotencyKey != null) {
                redisTemplate.opsForValue().set("idemp:" + idempotencyKey, reservationId);
            }
        }
        
        // 4. Set user counts in Redis
        for (Map.Entry<String, Integer> entry : userCounts.entrySet()) {
            redisTemplate.opsForValue().set(entry.getKey(), entry.getValue().toString());
        }

        log.info("Successfully rehydrated Redis with {} active reservations.", activeReservations.size());
    }
}

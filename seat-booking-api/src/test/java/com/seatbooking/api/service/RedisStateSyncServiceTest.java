package com.seatbooking.api.service;

import com.seatbooking.api.repository.ReservationRepository;
import com.seatbooking.common.entity.ReservationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisStateSyncServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private RedisStateSyncService redisStateSyncService;

    @BeforeEach
    void setUp() {
    }

    @Test
    void run_WithNoReservations_DoesNothing() {
        when(reservationRepository.findAllActive()).thenReturn(List.of());

        redisStateSyncService.run(null);

        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void run_WithActiveReservations_SyncsToRedis() {
        UUID showId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String idempKey = "idemp123";

        ReservationEntity res = new ReservationEntity();
        res.setShowId(showId);
        res.setUserId(userId);
        res.setReservationId(reservationId);
        res.setIdempotencyKey(idempKey);
        res.setSeats(List.of("A1", "A2"));
        res.setStatus("held");
        res.setExpiresAt(Instant.now().plusSeconds(300)); // Future expiry

        when(reservationRepository.findAllActive()).thenReturn(List.of(res));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        redisStateSyncService.run(null);

        // Verify seats are set
        verify(valueOperations).set("seat:" + showId + ":A1", reservationId.toString());
        verify(valueOperations).set("seat:" + showId + ":A2", reservationId.toString());

        // Verify zset for held seats
        verify(zSetOperations).add(eq("hold_expiry"), eq(showId + ":A1:" + reservationId), anyDouble());
        verify(zSetOperations).add(eq("hold_expiry"), eq(showId + ":A2:" + reservationId), anyDouble());

        // Verify idemp key
        verify(valueOperations).set("idemp:idemp123", reservationId.toString());

        // Verify user counts
        verify(valueOperations).set("user_count:" + showId + ":" + userId, "2");
    }
}

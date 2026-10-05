package com.seatbooking.api.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisAdmissionServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private DefaultRedisScript<List> reserveScript;

    @Mock
    private DefaultRedisScript<List> cancelScript;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RedisAdmissionService redisAdmissionService;

    @BeforeEach
    void setUp() {
        redisAdmissionService = new RedisAdmissionService(
                redisTemplate, reserveScript, cancelScript, meterRegistry);
    }

    @Test
    void attemptReservation_WhenOk_ReturnsOkStatus() {
        UUID showId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");
        String idempKey = "idemp123";
        int limit = 5;

        List<Object> scriptResult = List.of("OK");
        when(redisTemplate.execute(eq(reserveScript), any(List.class), any(Object[].class)))
                .thenReturn(scriptResult);

        var result = redisAdmissionService.attemptReservation(showId, userId, seats, idempKey, limit);

        assertThat(result.status()).isEqualTo(RedisAdmissionService.AdmissionResult.Status.OK);
        assertThat(result.reservationId()).isNotNull();
    }

    @Test
    void attemptReservation_WhenSeatTaken_ReturnsSeatTakenStatus() {
        UUID showId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");
        String idempKey = "idemp123";
        int limit = 5;

        List<Object> scriptResult = List.of("SEAT_TAKEN", "A1");
        when(redisTemplate.execute(eq(reserveScript), any(List.class), any(Object[].class)))
                .thenReturn(scriptResult);

        var result = redisAdmissionService.attemptReservation(showId, userId, seats, idempKey, limit);

        assertThat(result.status()).isEqualTo(RedisAdmissionService.AdmissionResult.Status.SEAT_TAKEN);
        assertThat(result.detail()).isEqualTo("A1");
    }

    @Test
    void cancelReservation_WhenOk_ReturnsTrue() {
        UUID showId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");
        String idempKey = "idemp123";
        String resId = "res123";

        List<Object> scriptResult = List.of("OK", "2");
        when(redisTemplate.execute(eq(cancelScript), any(List.class), any(Object[].class)))
                .thenReturn(scriptResult);

        boolean success = redisAdmissionService.cancelReservation(showId, userId, resId, seats, idempKey);

        assertThat(success).isTrue();
    }

    @Test
    void confirmReservation_RemovesFromZsetAndPersists() {
        UUID showId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");
        String idempKey = "idemp123";
        String resId = "res123";

        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        boolean success = redisAdmissionService.confirmReservation(showId, userId, resId, seats, idempKey);

        assertThat(success).isTrue();

        verify(zSetOperations).remove("hold_expiry", showId + ":A1:" + resId);
        verify(zSetOperations).remove("hold_expiry", showId + ":A2:" + resId);

        verify(redisTemplate).persist("seat:" + showId + ":A1");
        verify(redisTemplate).persist("seat:" + showId + ":A2");
        verify(redisTemplate).persist("idemp:idemp123");
        verify(redisTemplate).persist("user_count:" + showId + ":" + userId);
    }
}

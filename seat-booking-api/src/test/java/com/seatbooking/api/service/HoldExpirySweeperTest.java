package com.seatbooking.api.service;

import com.seatbooking.common.event.ReservationEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HoldExpirySweeperTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private KafkaTemplate<String, ReservationEvent> kafkaTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private HoldExpirySweeper sweeper;

    @Captor
    private ArgumentCaptor<ReservationEvent> eventCaptor;

    @BeforeEach
    void setUp() {
        sweeper = new HoldExpirySweeper(redisTemplate, kafkaTemplate, meterRegistry);
        ReflectionTestUtils.setField(sweeper, "batchSize", 100);
    }

    @Test
    void sweep_WhenLockNotAcquired_ReturnsEarly() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("sweeper_lock"), eq("locked"), any(Duration.class))).thenReturn(false);

        sweeper.sweep();

        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void sweep_WhenLockAcquired_ProcessesExpiredItems() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("sweeper_lock"), eq("locked"), any(Duration.class))).thenReturn(true);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn("show123:A1:res123");

        when(zSetOperations.rangeByScoreWithScores(eq("hold_expiry"), eq(0.0), anyDouble(), eq(0L), eq(100L)))
                .thenReturn(Set.of(tuple));

        when(valueOperations.get("seat:show123:A1")).thenReturn("res123");

        sweeper.sweep();

        // Verifies seat deletion
        verify(redisTemplate).delete("seat:show123:A1");

        // Verifies ZSET cleanup
        verify(zSetOperations).remove("hold_expiry", "show123:A1:res123");

        // Verifies Kafka event
        verify(kafkaTemplate).send(eq("seat-expirations"), eq("show123"), eventCaptor.capture());
        ReservationEvent event = eventCaptor.getValue();
        assertThat(event.getReservationId()).isEqualTo("res123");
        assertThat(event.getSeats()).containsExactly("A1");
        assertThat(event.getStatus()).isEqualTo("expired");

        // Verifies SSE publish
        verify(redisTemplate).convertAndSend(eq("show_state:show123"), anyString());
    }

    @Test
    void sweep_WhenSeatHolderChanged_DoesNotDeleteSeat() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("sweeper_lock"), eq("locked"), any(Duration.class))).thenReturn(true);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn("show123:A1:res123");

        when(zSetOperations.rangeByScoreWithScores(eq("hold_expiry"), eq(0.0), anyDouble(), eq(0L), eq(100L)))
                .thenReturn(Set.of(tuple));

        // Note: The current holder is different (res999 instead of res123)
        when(valueOperations.get("seat:show123:A1")).thenReturn("res999");

        sweeper.sweep();

        // Should NOT delete the seat key since it belongs to someone else now
        verify(redisTemplate, never()).delete("seat:show123:A1");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(ReservationEvent.class));

        // But SHOULD clean up the ZSET entry
        verify(zSetOperations).remove("hold_expiry", "show123:A1:res123");
    }
}

package com.seatbooking.consumer.listener;

import com.seatbooking.common.entity.ReservationEntity;
import com.seatbooking.common.event.ReservationEvent;
import com.seatbooking.consumer.repository.ConsumerReservationRepository;
import com.seatbooking.consumer.repository.ConsumerSeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationEventListenerTest {

    @Mock
    private ConsumerReservationRepository reservationRepository;

    @Mock
    private ConsumerSeatRepository seatRepository;

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ReservationEventListener listener;

    @Captor
    private ArgumentCaptor<ReservationEntity> reservationCaptor;

    @BeforeEach
    void setUp() {
        listener = new ReservationEventListener(reservationRepository, seatRepository, meterRegistry);
        ReflectionTestUtils.setField(listener, "holdTtlSeconds", 900);
    }

    @Test
    void handleReservationEvent_Held_SavesReservationAndUpdatesSeats() {
        UUID resId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");

        ReservationEvent event = ReservationEvent.builder()
                .reservationId(resId.toString())
                .showId(showId.toString())
                .userId(userId.toString())
                .seats(seats)
                .amount(200L)
                .status("held")
                .eventType(ReservationEvent.EventType.HELD)
                .idempotencyKey("idemp1")
                .timestamp(Instant.now())
                .build();

        when(reservationRepository.existsById(resId)).thenReturn(false);
        when(seatRepository.updateSeatStatus(any(), any(), any(), any())).thenReturn(1);

        listener.handleReservationEvent(event);

        verify(reservationRepository).save(reservationCaptor.capture());
        ReservationEntity saved = reservationCaptor.getValue();
        assertThat(saved.getReservationId()).isEqualTo(resId);
        assertThat(saved.getStatus()).isEqualTo("held");

        verify(seatRepository).updateSeatStatus(showId, "A1", "held", resId);
        verify(seatRepository).updateSeatStatus(showId, "A2", "held", resId);
    }

    @Test
    void handleReservationEvent_Confirmed_UpdatesReservationAndSeats() {
        UUID resId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");

        ReservationEvent event = ReservationEvent.builder()
                .reservationId(resId.toString())
                .showId(showId.toString())
                .userId(userId.toString())
                .seats(seats)
                .amount(200L)
                .status("confirmed")
                .eventType(ReservationEvent.EventType.CONFIRMED)
                .idempotencyKey("idemp1")
                .timestamp(Instant.now())
                .build();

        ReservationEntity existing = new ReservationEntity();
        existing.setReservationId(resId);
        existing.setStatus("held");

        when(reservationRepository.existsById(resId)).thenReturn(true);
        when(reservationRepository.findById(resId)).thenReturn(Optional.of(existing));

        listener.handleReservationEvent(event);

        verify(reservationRepository).save(reservationCaptor.capture());
        ReservationEntity saved = reservationCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("confirmed");

        verify(seatRepository).updateSeatStatus(showId, "A1", "confirmed", resId);
        verify(seatRepository).updateSeatStatus(showId, "A2", "confirmed", resId);
    }

    @Test
    void handleReservationEvent_Cancelled_UpdatesReservationAndReleasesSeats() {
        UUID resId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");

        ReservationEvent event = ReservationEvent.builder()
                .reservationId(resId.toString())
                .showId(showId.toString())
                .userId(UUID.randomUUID().toString())
                .seats(seats)
                .eventType(ReservationEvent.EventType.CANCELLED)
                .build();

        ReservationEntity existing = new ReservationEntity();
        existing.setReservationId(resId);

        when(reservationRepository.findById(resId)).thenReturn(Optional.of(existing));

        listener.handleReservationEvent(event);

        verify(reservationRepository).save(reservationCaptor.capture());
        ReservationEntity saved = reservationCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("cancelled");

        verify(seatRepository).releaseSeat(showId, "A1", resId);
        verify(seatRepository).releaseSeat(showId, "A2", resId);
    }

    @Test
    void handleExpirationEvent_UpdatesReservationAndReleasesSeats() {
        UUID resId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        List<String> seats = List.of("A1", "A2");

        ReservationEvent event = ReservationEvent.builder()
                .reservationId(resId.toString())
                .showId(showId.toString())
                .userId(UUID.randomUUID().toString())
                .seats(seats)
                .eventType(ReservationEvent.EventType.EXPIRED)
                .build();

        ReservationEntity existing = new ReservationEntity();
        existing.setReservationId(resId);

        when(reservationRepository.findById(resId)).thenReturn(Optional.of(existing));

        listener.handleExpirationEvent(event);

        verify(reservationRepository).save(reservationCaptor.capture());
        ReservationEntity saved = reservationCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("expired");

        verify(seatRepository).releaseSeat(showId, "A1", resId);
        verify(seatRepository).releaseSeat(showId, "A2", resId);
    }
}

package com.seatbooking.api.service;

import com.seatbooking.api.exception.IdempotencyConflictException;
import com.seatbooking.api.exception.InvalidSeatException;
import com.seatbooking.api.exception.SeatTakenException;
import com.seatbooking.api.repository.ReservationRepository;
import com.seatbooking.api.repository.SeatRepository;
import com.seatbooking.api.repository.ShowRepository;
import com.seatbooking.common.dto.ReservationResponse;
import com.seatbooking.common.dto.ReserveSeatsRequest;
import com.seatbooking.common.entity.ReservationEntity;
import com.seatbooking.common.entity.SeatEntity;
import com.seatbooking.common.entity.ShowEntity;
import com.seatbooking.common.entity.UserEntity;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private RedisAdmissionService admissionService;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private KafkaTemplate<String, ReservationEvent> kafkaTemplate;
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ReservationService reservationService;

    private UserEntity user;
    private ShowEntity show;

    @Captor
    private ArgumentCaptor<ReservationEvent> eventCaptor;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                admissionService, showRepository, seatRepository, reservationRepository,
                kafkaTemplate, stringRedisTemplate, meterRegistry);

        user = new UserEntity();
        user.setUserId(UUID.randomUUID());
        user.setEmail("test@example.com");

        show = new ShowEntity();
        show.setShowId(UUID.randomUUID());
        show.setName("Inception");
        show.setPrice(100L);
        show.setPerUserLimit(5);
    }

    @Test
    void reserveSeats_WhenSeatsInvalid_ThrowsException() {
        UUID showId = show.getShowId();
        ReserveSeatsRequest request = new ReserveSeatsRequest();
        request.setSeats(List.of("X99"));

        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(seatRepository.findByShowId(showId)).thenReturn(List.of(
                SeatEntity.builder().seatId("A1").build()
        ));

        assertThrows(InvalidSeatException.class, () ->
                reservationService.reserveSeats(user, showId, request));
    }

    @Test
    void reserveSeats_WhenOk_ReturnsResponseAndProducesKafkaAndSSE() {
        UUID showId = show.getShowId();
        ReserveSeatsRequest request = new ReserveSeatsRequest();
        request.setSeats(List.of("A1", "A2"));
        request.setIdempotencyKey("idemp123");

        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(seatRepository.findByShowId(showId)).thenReturn(List.of(
                SeatEntity.builder().seatId("A1").build(),
                SeatEntity.builder().seatId("A2").build()
        ));

        String resId = UUID.randomUUID().toString();
        when(admissionService.attemptReservation(showId, user.getUserId(), List.of("A1", "A2"), "idemp123", 5))
                .thenReturn(new RedisAdmissionService.AdmissionResult(RedisAdmissionService.AdmissionResult.Status.OK, resId, null));

        when(kafkaTemplate.send(anyString(), anyString(), any(ReservationEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null)); // CompletableFuture used in async send

        ReservationResponse response = reservationService.reserveSeats(user, showId, request);

        assertThat(response.getReservationId()).isEqualTo(resId);
        assertThat(response.getAmount()).isEqualTo(200L); // 2 seats * 100
        assertThat(response.getStatus()).isEqualTo("held");

        verify(kafkaTemplate).send(eq("reservations"), eq(showId.toString()), eventCaptor.capture());
        ReservationEvent event = eventCaptor.getValue();
        assertThat(event.getSeats()).containsExactly("A1", "A2");
        assertThat(event.getEventType()).isEqualTo(ReservationEvent.EventType.HELD);

        verify(stringRedisTemplate).convertAndSend(eq("show_state:" + showId), contains("A1"));
        verify(stringRedisTemplate).convertAndSend(eq("show_state:" + showId), contains("A2"));
    }

    @Test
    void reserveSeats_WhenSeatTaken_ThrowsException() {
        UUID showId = show.getShowId();
        ReserveSeatsRequest request = new ReserveSeatsRequest();
        request.setSeats(List.of("A1"));

        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(seatRepository.findByShowId(showId)).thenReturn(List.of(
                SeatEntity.builder().seatId("A1").build()
        ));

        when(admissionService.attemptReservation(showId, user.getUserId(), List.of("A1"), null, 5))
                .thenReturn(new RedisAdmissionService.AdmissionResult(RedisAdmissionService.AdmissionResult.Status.SEAT_TAKEN, null, "A1"));

        assertThrows(SeatTakenException.class, () ->
                reservationService.reserveSeats(user, showId, request));
    }

    @Test
    void reserveSeats_WhenIdempotentHitDifferentSeats_ThrowsConflict() {
        UUID showId = show.getShowId();
        ReserveSeatsRequest request = new ReserveSeatsRequest();
        request.setSeats(List.of("A1", "A2"));
        request.setIdempotencyKey("idemp123");

        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(seatRepository.findByShowId(showId)).thenReturn(List.of(
                SeatEntity.builder().seatId("A1").build(),
                SeatEntity.builder().seatId("A2").build(),
                SeatEntity.builder().seatId("A3").build()
        ));

        String resId = UUID.randomUUID().toString();
        when(admissionService.attemptReservation(showId, user.getUserId(), List.of("A1", "A2"), "idemp123", 5))
                .thenReturn(new RedisAdmissionService.AdmissionResult(RedisAdmissionService.AdmissionResult.Status.IDEMPOTENT_HIT, resId, null));

        ReservationEntity existingRes = new ReservationEntity();
        existingRes.setReservationId(UUID.fromString(resId));
        existingRes.setSeats(List.of("A3")); // Different seats than request

        when(reservationRepository.findById(UUID.fromString(resId))).thenReturn(Optional.of(existingRes));

        assertThrows(IdempotencyConflictException.class, () ->
                reservationService.reserveSeats(user, showId, request));
    }
}

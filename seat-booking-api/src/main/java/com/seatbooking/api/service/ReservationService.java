package com.seatbooking.api.service;

import com.seatbooking.api.repository.ReservationRepository;
import com.seatbooking.api.repository.SeatRepository;
import com.seatbooking.api.repository.ShowRepository;
import com.seatbooking.common.dto.ReservationResponse;
import com.seatbooking.common.dto.ReserveSeatsRequest;
import com.seatbooking.common.entity.ReservationEntity;
import com.seatbooking.common.entity.ShowEntity;
import com.seatbooking.common.entity.UserEntity;
import com.seatbooking.common.event.ReservationEvent;
import com.seatbooking.api.exception.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ReservationService {

    private final RedisAdmissionService admissionService;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final KafkaTemplate<String, ReservationEvent> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    private final Counter confirmedCounter;
    private final Counter declinedSeatTakenCounter;
    private final Counter declinedUserLimitCounter;
    private final Counter declinedIdempotentCounter;
    private final Counter kafkaProduceFailureCounter;

    private static final String RESERVATIONS_TOPIC = "reservations";
    
    // Cache shows in memory to avoid DB hits during high-load bursts
    private final ConcurrentHashMap<UUID, ShowEntity> showCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> showSeatsCache = new ConcurrentHashMap<>();

    public ReservationService(
            RedisAdmissionService admissionService,
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            KafkaTemplate<String, ReservationEvent> kafkaTemplate,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        this.admissionService = admissionService;
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;

        this.confirmedCounter = Counter.builder("reservations.total")
                .tag("status", "confirmed")
                .tag("decline_reason", "none")
                .description("Total confirmed reservations")
                .register(meterRegistry);

        this.declinedSeatTakenCounter = Counter.builder("reservations.total")
                .tag("status", "declined")
                .tag("decline_reason", "seat_taken")
                .register(meterRegistry);

        this.declinedUserLimitCounter = Counter.builder("reservations.total")
                .tag("status", "declined")
                .tag("decline_reason", "user_limit")
                .register(meterRegistry);

        this.declinedIdempotentCounter = Counter.builder("reservations.total")
                .tag("status", "declined")
                .tag("decline_reason", "idempotent_replay")
                .register(meterRegistry);

        this.kafkaProduceFailureCounter = Counter.builder("kafka.produce.failures.total")
                .register(meterRegistry);
    }

    /**
     * Reserve seats for a user. The core flow:
     * 1. Validate show + seat IDs
     * 2. Execute Redis Lua script (atomic admission)
     * 3. Produce Kafka event (async write-behind to Postgres)
     * 4. Return result
     *
     * The 201 response is returned AFTER Redis succeeds.
     * Kafka write-behind is fire-and-forget from the user's perspective.
     */
    public ReservationResponse reserveSeats(UserEntity user, UUID showId, ReserveSeatsRequest request) {
        // 1. Validate show exists (use memory cache)
        ShowEntity show = showCache.computeIfAbsent(showId, k -> 
            showRepository.findById(k).orElseThrow(() -> new ShowNotFoundException(k)));

        // 2. Validate seat IDs belong to this show
        List<String> requestedSeats = request.getSeats();
        validateSeatIds(showId, requestedSeats);

        // Sort seats deterministically to avoid any potential ordering issues
        List<String> sortedSeats = new ArrayList<>(requestedSeats);
        Collections.sort(sortedSeats);

        // 3. Execute atomic admission via Redis Lua
        RedisAdmissionService.AdmissionResult result = admissionService.attemptReservation(
                showId,
                user.getUserId(),
                sortedSeats,
                request.getIdempotencyKey(),
                show.getPerUserLimit()
        );

        // 4. Handle result
        return switch (result.status()) {
            case OK -> {
                confirmedCounter.increment();
                long amount = show.getPrice() * sortedSeats.size();

                // Produce Kafka event for async persistence as HELD
                produceReservationEvent(result.reservationId(), showId, user.getUserId(),
                        sortedSeats, amount, "held", request.getIdempotencyKey(),
                        ReservationEvent.EventType.HELD);

                // Publish real-time SSE update to Redis Pub/Sub as HELD
                for (String seatId : sortedSeats) {
                    String ssePayload = String.format("{\"seatId\":\"%s\",\"status\":\"held\"}", seatId);
                    redisTemplate.convertAndSend("show_state:" + showId, ssePayload);
                }

                yield ReservationResponse.builder()
                        .reservationId(result.reservationId())
                        .showId(showId.toString())
                        .userId(user.getUserId().toString())
                        .seats(sortedSeats)
                        .amount(amount)
                        .status("held")
                        .idempotencyKey(request.getIdempotencyKey())
                        .build();
            }
            case IDEMPOTENT_HIT -> {
                declinedIdempotentCounter.increment();
                // Return the original reservation
                yield fetchExistingReservation(result.reservationId(), request.getIdempotencyKey(),
                        sortedSeats, show);
            }
            case SEAT_TAKEN -> {
                declinedSeatTakenCounter.increment();
                throw new SeatTakenException(result.detail());
            }
            case USER_LIMIT_EXCEEDED -> {
                declinedUserLimitCounter.increment();
                throw new UserLimitExceededException(
                        user.getEmail(), show.getName(), show.getPerUserLimit());
            }
        };
    }

    /**
     * Cancel a reservation.
     */
    public ReservationResponse cancelReservation(UserEntity user, UUID reservationId) {
        // Try to find in Postgres first (might not be there yet if Kafka hasn't consumed)
        ReservationEntity reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // Verify ownership — identity from token only
        if (!reservation.getUserId().equals(user.getUserId())) {
            throw new ForbiddenException("You can only cancel your own reservations");
        }

        // Verify cancellable status
        if ("cancelled".equals(reservation.getStatus()) || "expired".equals(reservation.getStatus())) {
            throw new ConflictException("Reservation is already " + reservation.getStatus());
        }

        // Cancel in Redis atomically
        boolean cancelled = admissionService.cancelReservation(
                reservation.getShowId(),
                user.getUserId(),
                reservationId.toString(),
                reservation.getSeats(),
                reservation.getIdempotencyKey()
        );

        // Produce Kafka cancel event
        produceReservationEvent(
                reservationId.toString(),
                reservation.getShowId(),
                user.getUserId(),
                reservation.getSeats(),
                reservation.getAmount(),
                "cancelled",
                reservation.getIdempotencyKey(),
                ReservationEvent.EventType.CANCELLED
        );

        // Publish real-time SSE update to Redis Pub/Sub
        for (String seatId : reservation.getSeats()) {
            String ssePayload = String.format("{\"seatId\":\"%s\",\"status\":\"available\"}", seatId);
            redisTemplate.convertAndSend("show_state:" + reservation.getShowId(), ssePayload);
        }

        return ReservationResponse.builder()
                .reservationId(reservationId.toString())
                .showId(reservation.getShowId().toString())
                .userId(user.getUserId().toString())
                .seats(reservation.getSeats())
                .amount(reservation.getAmount())
                .status("cancelled")
                .idempotencyKey(reservation.getIdempotencyKey())
                .build();
    }

    /**
     * Confirm a held reservation.
     */
    public ReservationResponse confirmReservation(UserEntity user, UUID reservationId) {
        ReservationEntity reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (!reservation.getUserId().equals(user.getUserId())) {
            throw new ForbiddenException("You can only confirm your own reservations");
        }

        if (!"held".equals(reservation.getStatus())) {
            throw new ConflictException("Reservation is not in held status");
        }

        // Confirm in Redis
        admissionService.confirmReservation(
                reservation.getShowId(),
                user.getUserId(),
                reservationId.toString(),
                reservation.getSeats(),
                reservation.getIdempotencyKey()
        );

        // Produce Kafka confirm event
        produceReservationEvent(
                reservationId.toString(),
                reservation.getShowId(),
                user.getUserId(),
                reservation.getSeats(),
                reservation.getAmount(),
                "confirmed",
                reservation.getIdempotencyKey(),
                ReservationEvent.EventType.CONFIRMED
        );

        // Publish real-time SSE update to Redis Pub/Sub
        for (String seatId : reservation.getSeats()) {
            String ssePayload = String.format("{\"seatId\":\"%s\",\"status\":\"confirmed\"}", seatId);
            redisTemplate.convertAndSend("show_state:" + reservation.getShowId(), ssePayload);
        }

        return ReservationResponse.builder()
                .reservationId(reservationId.toString())
                .showId(reservation.getShowId().toString())
                .userId(user.getUserId().toString())
                .seats(reservation.getSeats())
                .amount(reservation.getAmount())
                .status("confirmed")
                .idempotencyKey(reservation.getIdempotencyKey())
                .build();
    }

    /**
     * Get reservation details (owner only).
     */
    public ReservationResponse getReservation(UserEntity user, UUID reservationId) {
        ReservationEntity reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (!reservation.getUserId().equals(user.getUserId())) {
            throw new ForbiddenException("You can only view your own reservations");
        }

        return ReservationResponse.builder()
                .reservationId(reservationId.toString())
                .showId(reservation.getShowId().toString())
                .userId(reservation.getUserId().toString())
                .seats(reservation.getSeats())
                .amount(reservation.getAmount())
                .status(reservation.getStatus())
                .idempotencyKey(reservation.getIdempotencyKey())
                .build();
    }

    /**
     * Get all active reservations for a user for a specific show.
     */
    public List<ReservationResponse> getMyReservationsForShow(UserEntity user, UUID showId) {
        return reservationRepository.findActiveByUserIdAndShowId(user.getUserId(), showId)
                .stream()
                .map(res -> ReservationResponse.builder()
                        .reservationId(res.getReservationId().toString())
                        .showId(res.getShowId().toString())
                        .userId(res.getUserId().toString())
                        .seats(res.getSeats())
                        .amount(res.getAmount())
                        .status(res.getStatus())
                        .idempotencyKey(res.getIdempotencyKey())
                        .build())
                .toList();
    }

    // --- Private helpers ---

    private void validateSeatIds(UUID showId, List<String> seatIds) {
        Set<String> validSeats = showSeatsCache.computeIfAbsent(showId, k -> {
            var existingSeats = seatRepository.findByShowId(k);
            Set<String> set = new HashSet<>();
            existingSeats.forEach(s -> set.add(s.getSeatId()));
            return set;
        });
        
        List<String> invalid = seatIds.stream()
                .filter(id -> !validSeats.contains(id))
                .toList();
                
        if (!invalid.isEmpty()) {
            throw new InvalidSeatException(invalid);
        }
    }

    private ReservationResponse fetchExistingReservation(
            String existingReservationId, String idempotencyKey,
            List<String> requestedSeats, ShowEntity show) {

        // Check if the idempotent hit is for the same seats (same-key-different-body detection)
        Optional<ReservationEntity> existing = reservationRepository
                .findById(UUID.fromString(existingReservationId));

        if (existing.isPresent()) {
            ReservationEntity res = existing.get();
            // Same key, different seats → 409 conflict
            List<String> existingSeats = new ArrayList<>(res.getSeats());
            Collections.sort(existingSeats);
            if (!existingSeats.equals(requestedSeats)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return ReservationResponse.builder()
                    .reservationId(res.getReservationId().toString())
                    .showId(res.getShowId().toString())
                    .userId(res.getUserId().toString())
                    .seats(res.getSeats())
                    .amount(res.getAmount())
                    .status(res.getStatus())
                    .idempotencyKey(res.getIdempotencyKey())
                    .build();
        }

        // Reservation not in Postgres yet (Kafka hasn't consumed it) — return from Redis data
        return ReservationResponse.builder()
                .reservationId(existingReservationId)
                .showId(show.getShowId().toString())
                .seats(requestedSeats)
                .amount(show.getPrice() * requestedSeats.size())
                .status("confirmed")
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private void produceReservationEvent(
            String reservationId, UUID showId, UUID userId,
            List<String> seats, long amount, String status,
            String idempotencyKey, ReservationEvent.EventType eventType) {

        ReservationEvent event = ReservationEvent.builder()
                .reservationId(reservationId)
                .showId(showId.toString())
                .userId(userId.toString())
                .seats(seats)
                .amount(amount)
                .status(status)
                .idempotencyKey(idempotencyKey)
                .eventType(eventType)
                .timestamp(Instant.now())
                .build();

        // Key by showId for partition ordering
        kafkaTemplate.send(RESERVATIONS_TOPIC, showId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        kafkaProduceFailureCounter.increment();
                        log.error("Failed to produce Kafka event: reservationId={} error={}",
                                reservationId, ex.getMessage());
                    } else {
                        log.debug("Kafka event produced: reservationId={} partition={} offset={}",
                                reservationId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}

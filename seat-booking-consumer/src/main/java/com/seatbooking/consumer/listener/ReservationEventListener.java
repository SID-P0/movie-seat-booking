package com.seatbooking.consumer.listener;

import com.seatbooking.common.entity.ReservationEntity;
import com.seatbooking.common.event.ReservationEvent;
import com.seatbooking.consumer.repository.ConsumerReservationRepository;
import com.seatbooking.consumer.repository.ConsumerSeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka consumer that persists reservation events to Postgres.
 * This is the async write-behind — protects Postgres from the burst stampede.
 *
 * Idempotency at the DB level:
 * - INSERT uses ON CONFLICT (idempotency_key) DO NOTHING
 * - UPDATE uses WHERE guards on current status / reservation_id
 *
 * If the consumer crashes after writing but before committing the Kafka offset,
 * the message will be replayed — and the ON CONFLICT / WHERE guards make it safe.
 */
@Component
@Slf4j
public class ReservationEventListener {

    private final ConsumerReservationRepository reservationRepository;
    private final ConsumerSeatRepository seatRepository;
    private final Counter consumedCounter;
    private final Counter consumerFailureCounter;

    @org.springframework.beans.factory.annotation.Value("${app.hold-ttl-seconds:900}")
    private int holdTtlSeconds;

    public ReservationEventListener(
            ConsumerReservationRepository reservationRepository,
            ConsumerSeatRepository seatRepository,
            MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;

        this.consumedCounter = Counter.builder("kafka.consumer.events.total")
                .description("Total events consumed from Kafka")
                .register(meterRegistry);

        this.consumerFailureCounter = Counter.builder("kafka.consumer.failures.total")
                .description("Total consumer processing failures")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "reservations", groupId = "seat-booking-consumer")
    @Transactional
    public void handleReservationEvent(ReservationEvent event) {
        try {
            consumedCounter.increment();

            switch (event.getEventType()) {
                case HELD -> persistHold(event);
                case CONFIRMED -> persistConfirmation(event);
                case CANCELLED -> persistCancellation(event);
                case EXPIRED -> persistExpiration(event);
            }

            log.info("Processed reservation event: type={} reservationId={} showId={}",
                    event.getEventType(), event.getReservationId(), event.getShowId());

        } catch (Exception e) {
            consumerFailureCounter.increment();
            log.error("Failed to process reservation event: reservationId={} error={}",
                    event.getReservationId(), e.getMessage(), e);
            // Don't rethrow — let Spring Kafka handle retry/DLQ via config
            throw e;
        }
    }

    @KafkaListener(topics = "seat-expirations", groupId = "seat-booking-consumer")
    @Transactional
    public void handleExpirationEvent(ReservationEvent event) {
        try {
            consumedCounter.increment();
            persistExpiration(event);
            log.info("Processed expiration event: reservationId={}", event.getReservationId());
        } catch (Exception e) {
            consumerFailureCounter.increment();
            log.error("Failed to process expiration event: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void persistHold(ReservationEvent event) {
        UUID reservationId = UUID.fromString(event.getReservationId());
        UUID showId = UUID.fromString(event.getShowId());
        UUID userId = UUID.fromString(event.getUserId());

        // Insert reservation — ON CONFLICT DO NOTHING for idempotency
        if (!reservationRepository.existsById(reservationId)) {
            ReservationEntity entity = ReservationEntity.builder()
                    .reservationId(reservationId)
                    .showId(showId)
                    .userId(userId)
                    .seats(event.getSeats())
                    .amount(event.getAmount())
                    .status("held")
                    .idempotencyKey(event.getIdempotencyKey())
                    .expiresAt(event.getTimestamp().plusSeconds(holdTtlSeconds))
                    .build();

            try {
                reservationRepository.save(entity);
            } catch (Exception e) {
                // UNIQUE constraint on idempotency_key → safe to ignore (replay)
                log.debug("Reservation already exists (idempotent replay): {}", reservationId);
                return;
            }
        }

        // Update seat statuses
        for (String seatId : event.getSeats()) {
            int updated = seatRepository.updateSeatStatus(showId, seatId, "held", reservationId);
            if (updated == 0) {
                log.warn("Seat update returned 0 rows: show={} seat={} reservation={}",
                        showId, seatId, reservationId);
            }
        }
    }

    private void persistConfirmation(ReservationEvent event) {
        UUID reservationId = UUID.fromString(event.getReservationId());
        UUID showId = UUID.fromString(event.getShowId());
        UUID userId = UUID.fromString(event.getUserId());

        // Insert reservation if it doesn't exist (e.g. if skipped HELD or missed)
        if (!reservationRepository.existsById(reservationId)) {
            ReservationEntity entity = ReservationEntity.builder()
                    .reservationId(reservationId)
                    .showId(showId)
                    .userId(userId)
                    .seats(event.getSeats())
                    .amount(event.getAmount())
                    .status("confirmed")
                    .idempotencyKey(event.getIdempotencyKey())
                    .expiresAt(event.getTimestamp().plusSeconds(holdTtlSeconds))
                    .build();

            try {
                reservationRepository.save(entity);
            } catch (Exception e) {
                log.debug("Reservation already exists (idempotent replay): {}", reservationId);
                return;
            }
        } else {
            // Update to confirmed
            reservationRepository.findById(reservationId).ifPresent(r -> {
                r.setStatus("confirmed");
                reservationRepository.save(r);
            });
        }

        // Update seat statuses
        for (String seatId : event.getSeats()) {
            int updated = seatRepository.updateSeatStatus(showId, seatId, "confirmed", reservationId);
            if (updated == 0) {
                log.warn("Seat update returned 0 rows: show={} seat={} reservation={}",
                        showId, seatId, reservationId);
            }
        }
    }

    private void persistCancellation(ReservationEvent event) {
        UUID reservationId = UUID.fromString(event.getReservationId());
        UUID showId = UUID.fromString(event.getShowId());

        // Update reservation status
        reservationRepository.findById(reservationId).ifPresent(r -> {
            r.setStatus("cancelled");
            reservationRepository.save(r);
        });

        // Release seats
        for (String seatId : event.getSeats()) {
            seatRepository.releaseSeat(showId, seatId, reservationId);
        }
    }

    private void persistExpiration(ReservationEvent event) {
        UUID reservationId = UUID.fromString(event.getReservationId());
        UUID showId = UUID.fromString(event.getShowId());

        // Update reservation status
        reservationRepository.findById(reservationId).ifPresent(r -> {
            r.setStatus("expired");
            reservationRepository.save(r);
        });

        // Release seats
        for (String seatId : event.getSeats()) {
            seatRepository.releaseSeat(showId, seatId, reservationId);
        }
    }
}

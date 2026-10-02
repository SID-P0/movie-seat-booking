package com.seatbooking.common.event;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Kafka event for reservation state changes.
 * Produced by the API app, consumed by the DB worker.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationEvent implements Serializable {

    public enum EventType {
        HELD,
        CONFIRMED,
        CANCELLED,
        EXPIRED
    }

    private String reservationId;
    private String showId;
    private String userId;
    private List<String> seats;
    private long amount;
    private String status;
    private String idempotencyKey;
    private EventType eventType;
    private Instant timestamp;
}

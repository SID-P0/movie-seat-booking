package com.seatbooking.common.dto;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationResponse {
    private String reservationId;
    private String showId;
    private String userId;
    private List<String> seats;
    private long amount;
    private String status;
    private String idempotencyKey;
}

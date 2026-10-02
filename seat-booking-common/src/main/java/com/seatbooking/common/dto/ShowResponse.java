package com.seatbooking.common.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowResponse {
    private String showId;
    private String name;
    private int totalSeats;
    private long price;
    private int perUserLimit;
    private Map<String, String> seatMap;  // seat_id -> status
    private SeatCounts counts;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SeatCounts {
        private int available;
        private int held;
        private int confirmed;
        private int total;
    }
}

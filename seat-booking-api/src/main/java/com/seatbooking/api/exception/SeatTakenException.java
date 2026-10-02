package com.seatbooking.api.exception;

public class SeatTakenException extends RuntimeException {
    private final String seatId;

    public SeatTakenException(String seatId) {
        super("Seat already taken: " + seatId);
        this.seatId = seatId;
    }

    public String getSeatId() {
        return seatId;
    }
}

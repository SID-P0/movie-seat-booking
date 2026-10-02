package com.seatbooking.api.exception;

import java.util.List;

public class InvalidSeatException extends RuntimeException {
    public InvalidSeatException(List<String> invalidSeats) {
        super("Invalid seat IDs: " + invalidSeats);
    }
}

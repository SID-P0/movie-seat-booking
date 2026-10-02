package com.seatbooking.api.exception;

public class UserLimitExceededException extends RuntimeException {
    public UserLimitExceededException(String userEmail, String showName, int limit) {
        super(String.format("User '%s' has reached the per-user limit of %d seats for show '%s'",
                userEmail, limit, showName));
    }
}

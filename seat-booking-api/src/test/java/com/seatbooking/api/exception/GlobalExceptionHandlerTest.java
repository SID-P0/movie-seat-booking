package com.seatbooking.api.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {
        @GetMapping("/test/seat-taken")
        public void seatTaken() {
            throw new SeatTakenException("A1");
        }

        @GetMapping("/test/user-limit")
        public void userLimit() {
            throw new UserLimitExceededException("user123", "show123", 5);
        }

        @GetMapping("/test/not-found")
        public void notFound() {
            throw new ShowNotFoundException(java.util.UUID.randomUUID());
        }

        @GetMapping("/test/invalid-seat")
        public void invalidSeat() {
            throw new InvalidSeatException(java.util.List.of("Z99"));
        }

        @GetMapping("/test/generic")
        public void generic() throws Exception {
            throw new Exception("Some random error");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void handleSeatTaken_ReturnsConflict() throws Exception {
        MDC.put("requestId", "req-123");
        mockMvc.perform(get("/test/seat-taken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("seat_taken"))
                .andExpect(jsonPath("$.message").value("Seat already taken: A1"));
    }

    @Test
    void handleUserLimit_ReturnsConflict() throws Exception {
        mockMvc.perform(get("/test/user-limit"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("per_user_limit_exceeded"));
    }

    @Test
    void handleShowNotFound_ReturnsNotFound() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void handleInvalidSeat_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/invalid-seat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_seats"));
    }

    @Test
    void handleGeneric_ReturnsInternalServerError() throws Exception {
        mockMvc.perform(get("/test/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}

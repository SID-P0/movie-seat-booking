package com.seatbooking.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.api.filter.AuthTokenFilter;
import com.seatbooking.api.service.ReservationService;
import com.seatbooking.common.dto.ReservationResponse;
import com.seatbooking.common.dto.ReserveSeatsRequest;
import com.seatbooking.common.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @MockBean
    private com.seatbooking.api.repository.UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UserEntity mockUser;
    private UUID showId;
    private UUID reservationId;
    private ReservationResponse mockReservationResponse;

    @BeforeEach
    void setUp() {
        mockUser = new UserEntity();
        mockUser.setUserId(UUID.randomUUID());
        mockUser.setEmail("test@example.com");

        showId = UUID.randomUUID();
        reservationId = UUID.randomUUID();

        mockReservationResponse = new ReservationResponse();
        mockReservationResponse.setReservationId(reservationId.toString());
        mockReservationResponse.setShowId(showId.toString());
        mockReservationResponse.setStatus("HELD");
        mockReservationResponse.setSeats(List.of("A1", "A2"));
    }

    @Test
    void reserveSeats_WhenAuthenticated_ReturnsCreated() throws Exception {
        ReserveSeatsRequest request = new ReserveSeatsRequest();
        request.setSeats(List.of("A1", "A2"));
        request.setIdempotencyKey(UUID.randomUUID().toString());

        when(reservationService.reserveSeats(eq(mockUser), eq(showId), any(ReserveSeatsRequest.class)))
                .thenReturn(mockReservationResponse);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .requestAttr(AuthTokenFilter.USER_ATTR, mockUser))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void reserveSeats_WhenNotAuthenticated_ReturnsForbidden() throws Exception {
        ReserveSeatsRequest request = new ReserveSeatsRequest();
        request.setSeats(List.of("A1", "A2"));
        request.setIdempotencyKey(UUID.randomUUID().toString());

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelReservation_ReturnsOk() throws Exception {
        mockReservationResponse.setStatus("CANCELLED");
        when(reservationService.cancelReservation(eq(mockUser), eq(reservationId)))
                .thenReturn(mockReservationResponse);

        mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                        .requestAttr(AuthTokenFilter.USER_ATTR, mockUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void confirmReservation_ReturnsOk() throws Exception {
        mockReservationResponse.setStatus("CONFIRMED");
        when(reservationService.confirmReservation(eq(mockUser), eq(reservationId)))
                .thenReturn(mockReservationResponse);

        mockMvc.perform(post("/reservations/" + reservationId + "/confirm")
                        .requestAttr(AuthTokenFilter.USER_ATTR, mockUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void getReservation_ReturnsOk() throws Exception {
        when(reservationService.getReservation(eq(mockUser), eq(reservationId)))
                .thenReturn(mockReservationResponse);

        mockMvc.perform(get("/reservations/" + reservationId)
                        .requestAttr(AuthTokenFilter.USER_ATTR, mockUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()));
    }

    @Test
    void getMyReservationsForShow_ReturnsList() throws Exception {
        when(reservationService.getMyReservationsForShow(eq(mockUser), eq(showId)))
                .thenReturn(List.of(mockReservationResponse));

        mockMvc.perform(get("/shows/" + showId + "/reservations/mine")
                        .requestAttr(AuthTokenFilter.USER_ATTR, mockUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reservationId").value(reservationId.toString()));
    }
}

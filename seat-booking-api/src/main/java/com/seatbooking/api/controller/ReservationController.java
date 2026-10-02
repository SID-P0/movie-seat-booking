package com.seatbooking.api.controller;

import com.seatbooking.api.filter.AuthTokenFilter;
import com.seatbooking.api.service.ReservationService;
import com.seatbooking.common.dto.ReservationResponse;
import com.seatbooking.common.dto.ReserveSeatsRequest;
import com.seatbooking.common.dto.CancelReservationRequest;
import com.seatbooking.common.dto.ConfirmReservationRequest;
import com.seatbooking.common.entity.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * POST /shows/{id}/reserve — Reserve seats (authenticated user)
     */
    @Operation(summary = "Reserve Seats", description = "Atomically reserve seats for a show. Identity is derived purely from the Bearer token.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Successfully reserved seats"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Missing or invalid token"),
            @ApiResponse(responseCode = "409", description = "Conflict - Seats already taken or limit exceeded")
    })
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
            @PathVariable("id") UUID showId,
            @Valid @RequestBody ReserveSeatsRequest request,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);

        ReservationResponse response = reservationService.reserveSeats(user, showId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /reservations/{id}/cancel — Cancel a reservation (owner only)
     */
    @Operation(summary = "Cancel Reservation", description = "Cancel a reservation you own. Does not require a body.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully cancelled"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - Not your reservation"),
            @ApiResponse(responseCode = "404", description = "Reservation not found"),
            @ApiResponse(responseCode = "409", description = "Conflict - Already cancelled")
    })
    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancelReservation(
            @PathVariable("id") UUID reservationId,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);

        ReservationResponse response = reservationService.cancelReservation(user, reservationId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /reservations/{id}/confirm — Confirm a held reservation (place order)
     */
    @Operation(summary = "Confirm Reservation", description = "Confirm a held reservation. Does not require a body.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully confirmed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - Not your reservation"),
            @ApiResponse(responseCode = "404", description = "Reservation not found"),
            @ApiResponse(responseCode = "409", description = "Conflict - Not in held status")
    })
    @PostMapping("/reservations/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(
            @PathVariable("id") UUID reservationId,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);

        ReservationResponse response = reservationService.confirmReservation(user, reservationId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reservations/{id}")
    public ResponseEntity<ReservationResponse> getReservation(
            @PathVariable("id") UUID reservationId,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);

        ReservationResponse response = reservationService.getReservation(user, reservationId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /shows/{showId}/reservations/mine — Get all active reservations for a user for a specific show
     */
    @GetMapping("/shows/{showId}/reservations/mine")
    public ResponseEntity<List<ReservationResponse>> getMyReservationsForShow(
            @PathVariable("showId") UUID showId,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);
        List<ReservationResponse> responses = reservationService.getMyReservationsForShow(user, showId);
        return ResponseEntity.ok(responses);
    }

    private UserEntity getAuthenticatedUser(HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthTokenFilter.USER_ATTR);
        if (user == null) {
            throw new com.seatbooking.api.exception.ForbiddenException("Authentication required");
        }
        return user;
    }
}

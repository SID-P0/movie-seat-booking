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
     *
     * Identity comes from the auth token, NOT from the request body.
     */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
            @PathVariable("id") UUID showId,
            @Valid @RequestBody ReserveSeatsRequest request,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);

        if (!user.getUserId().toString().equals(request.getUserId())) {
            throw new com.seatbooking.api.exception.ForbiddenException("User ID mismatch");
        }

        ReservationResponse response = reservationService.reserveSeats(user, showId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /reservations/{id}/cancel — Cancel a reservation (owner only)
     */
    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancelReservation(
            @PathVariable("id") UUID reservationId,
            @Valid @RequestBody CancelReservationRequest request,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);

        if (!user.getUserId().toString().equals(request.getUserId())) {
            throw new com.seatbooking.api.exception.ForbiddenException("User ID mismatch");
        }

        ReservationResponse response = reservationService.cancelReservation(user, reservationId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /reservations/{id}/confirm — Confirm a held reservation (place order)
     */
    @PostMapping("/reservations/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(
            @PathVariable("id") UUID reservationId,
            @Valid @RequestBody ConfirmReservationRequest request,
            HttpServletRequest httpRequest) {

        UserEntity user = getAuthenticatedUser(httpRequest);

        if (!user.getUserId().toString().equals(request.getUserId())) {
            throw new com.seatbooking.api.exception.ForbiddenException("User ID mismatch");
        }

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

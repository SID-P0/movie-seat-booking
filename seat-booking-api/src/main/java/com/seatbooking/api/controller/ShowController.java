package com.seatbooking.api.controller;

import com.seatbooking.api.filter.AuthTokenFilter;
import com.seatbooking.api.service.ShowService;
import com.seatbooking.common.dto.CreateShowRequest;
import com.seatbooking.common.dto.ShowResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ShowController {

    private final ShowService showService;

    /**
     * POST /shows — Create a show (admin only)
     */
    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> createShow(
            @Valid @RequestBody CreateShowRequest request,
            HttpServletRequest httpRequest) {

        // Admin auth check
        Boolean isAdmin = (Boolean) httpRequest.getAttribute(AuthTokenFilter.ADMIN_ATTR);
        if (isAdmin == null || !isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ShowResponse response = showService.createShow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /shows/{id} — Show state with seat map and counts
     */
    @GetMapping("/shows/{id}")
    public ResponseEntity<ShowResponse> getShow(@PathVariable("id") UUID showId) {
        ShowResponse response = showService.getShow(showId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /shows — List all shows
     */
    @GetMapping("/shows")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> getAllShows() {
        return ResponseEntity.ok(showService.getAllShows());
    }
}

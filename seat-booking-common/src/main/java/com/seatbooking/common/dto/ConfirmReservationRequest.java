package com.seatbooking.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmReservationRequest {
    @NotBlank(message = "User ID is required")
    @io.swagger.v3.oas.annotations.media.Schema(description = "ID of the user confirming the reservation", example = "123e4567-e89b-12d3-a456-426614174000")
    private String userId;
}

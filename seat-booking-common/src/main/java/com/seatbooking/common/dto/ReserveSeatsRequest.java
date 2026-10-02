package com.seatbooking.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReserveSeatsRequest {

    @NotEmpty(message = "At least one seat must be specified")
    @io.swagger.v3.oas.annotations.media.Schema(description = "List of seat IDs to book", example = "[\"A1\", \"A2\"]")
    private List<String> seats;

    @NotBlank(message = "Idempotency key is required")
    @io.swagger.v3.oas.annotations.media.Schema(description = "Unique idempotency key for this request", example = "unique-uuid-1234")
    private String idempotencyKey;
}

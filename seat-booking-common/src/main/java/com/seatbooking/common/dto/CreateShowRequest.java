package com.seatbooking.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateShowRequest {

    @NotBlank(message = "Show name is required")
    @io.swagger.v3.oas.annotations.media.Schema(description = "Name of the show", example = "Avengers: Endgame")
    private String name;

    @NotEmpty(message = "At least one seat is required")
    @io.swagger.v3.oas.annotations.media.Schema(description = "List of seat IDs to create", example = "[\"A1\", \"A2\", \"A3\"]")
    private List<String> seats;

    @Positive(message = "Price must be positive")
    @io.swagger.v3.oas.annotations.media.Schema(description = "Price of a single ticket", example = "1500")
    private long price;
}

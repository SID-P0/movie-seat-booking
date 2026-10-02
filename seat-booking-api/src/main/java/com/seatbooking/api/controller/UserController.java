package com.seatbooking.api.controller;

import com.seatbooking.api.filter.AuthTokenFilter;
import com.seatbooking.api.repository.UserRepository;
import com.seatbooking.common.entity.UserEntity;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * User registration endpoint.
 * For the burst test, we pre-create users and return their raw tokens.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Email of the user (It could be just usersname as well email validation is not present)", example = "test@example.com")
        private String email;
    }

    @PostMapping("/users/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
        // Generate a random token
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = AuthTokenFilter.hashToken(rawToken);

        UserEntity user = UserEntity.builder()
                .email(request.getEmail())
                .tokenHash(tokenHash)
                .build();

        try {
            user = userRepository.save(user);
        } catch (Exception e) {
            // Email already exists — return conflict
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Email already registered"));
        }

        log.info("User registered: userId={} email={}", user.getUserId(), request.getEmail());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "user_id", user.getUserId().toString(),
                        "email", request.getEmail(),
                        "token", rawToken));
    }
}

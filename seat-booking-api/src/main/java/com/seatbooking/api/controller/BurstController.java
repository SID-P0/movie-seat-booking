package com.seatbooking.api.controller;

import com.seatbooking.api.repository.UserRepository;
import com.seatbooking.common.entity.UserEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.time.Duration;

import static com.seatbooking.api.filter.AuthTokenFilter.hashToken;

@RestController
@Slf4j
public class BurstController {

    private final UserRepository userRepository;
    private final HttpClient httpClient;
    private final Counter burstStartedCounter;

    public BurstController(UserRepository userRepository, MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.burstStartedCounter = Counter.builder("burst.test.started")
                .description("Number of times a burst test was triggered")
                .register(meterRegistry);
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor()) // Very fast concurrent connections
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostMapping("/test/burst/{showId}")
    public ResponseEntity<String> triggerBurst(
            @PathVariable("showId") String showId,
            @RequestParam(value = "count", defaultValue = "20000") int count) {

        burstStartedCounter.increment();
        log.info("Starting burst test of {} requests against show {}", count, showId);

        // Create 10 temporary valid users in DB
        List<String> validTokens = new ArrayList<>();
        List<String> validUserIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String token = "burst-token-" + UUID.randomUUID();
            UserEntity user = new UserEntity();
            user.setEmail("burst-" + UUID.randomUUID() + "@example.com");
            user.setTokenHash(hashToken(token));
            userRepository.save(user);
            validTokens.add(token);
            validUserIds.add(user.getUserId().toString());
        }

        // Fire requests async using a background thread
        Thread.ofVirtual().start(() -> {
            int totalThreads = 200; // Matches Tomcat's max-threads
            int requestsPerThread = count / totalThreads;

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < totalThreads; t++) {
                    executor.submit(() -> {
                        for (int i = 0; i < requestsPerThread; i++) {
                            boolean isValid = ThreadLocalRandom.current().nextInt(100) < 80;
                            
                            String token;
                            String userId;
                            if (isValid) {
                                int userIdx = ThreadLocalRandom.current().nextInt(validTokens.size());
                                token = validTokens.get(userIdx);
                                userId = validUserIds.get(userIdx);
                            } else {
                                token = "invalid-token-" + UUID.randomUUID();
                                userId = UUID.randomUUID().toString();
                            }

                            char row = (char) ('A' + ThreadLocalRandom.current().nextInt(10));
                            int num = 1 + ThreadLocalRandom.current().nextInt(10);
                            String seat = row + "" + num;
                            String idempotencyKey = UUID.randomUUID().toString();

                            String payload = String.format("{\"seats\":[\"%s\"],\"idempotencyKey\":\"%s\"}", seat, idempotencyKey);

                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:8080/shows/" + showId + "/reserve"))
                                    .header("Content-Type", "application/json")
                                    .header("Authorization", "Bearer " + token)
                                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                                    .build();

                            try {
                                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                            } catch (Exception e) {
                                // Ignore
                            }
                            
                            // Tiny sleep to yield CPU and let SSE flush
                            try { Thread.sleep(5); } catch (Exception e) {}
                        }
                    });
                }
            } // blocks until all 200 virtual threads finish
            log.info("Finished firing {} requests", count);
        });

        return ResponseEntity.ok("Burst of " + count + " requests initiated in the background!");
    }
}

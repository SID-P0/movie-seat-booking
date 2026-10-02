package com.seatbooking.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * SSE endpoint for real-time seat status updates.
 * Subscribes to Redis Pub/Sub channel for the requested show,
 * and forwards events to the client via Server-Sent Events.
 *
 * This handles multi-instance Spring Boot correctly because
 * Redis Pub/Sub broadcasts to all subscribers.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SseController {

    private final RedisMessageListenerContainer redisMessageListenerContainer;

    @GetMapping(value = "/shows/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamShowState(@PathVariable("id") UUID showId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        String channel = "show_state:" + showId;

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String payload = new String(message.getBody());
                emitter.send(SseEmitter.event()
                        .name("seat_update")
                        .data(payload));
            } catch (IOException e) {
                emitter.complete();
            }
        };

        ChannelTopic topic = new ChannelTopic(channel);
        redisMessageListenerContainer.addMessageListener(listener, topic);

        // Clean up on disconnect
        emitter.onCompletion(() -> {
            redisMessageListenerContainer.removeMessageListener(listener, topic);
            log.debug("SSE client disconnected from show {}", showId);
        });

        emitter.onTimeout(() -> {
            redisMessageListenerContainer.removeMessageListener(listener, topic);
            emitter.complete();
        });

        emitter.onError(e -> {
            redisMessageListenerContainer.removeMessageListener(listener, topic);
            emitter.complete();
        });

        log.debug("SSE client connected for show {}", showId);
        return emitter;
    }
}

package com.seatbooking.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SseController.class)
@AutoConfigureMockMvc(addFilters = false)
class SseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @MockBean
    private com.seatbooking.api.repository.UserRepository userRepository;

    @Test
    void streamShowState_ReturnsSseEmitter() throws Exception {
        UUID showId = UUID.randomUUID();

        mockMvc.perform(get("/shows/" + showId + "/stream"))
                .andExpect(status().isOk());

        // Verify that the listener was added to the container
        verify(redisMessageListenerContainer).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
    }
}

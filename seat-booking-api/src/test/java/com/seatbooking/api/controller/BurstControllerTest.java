package com.seatbooking.api.controller;

import com.seatbooking.api.repository.UserRepository;
import com.seatbooking.common.entity.UserEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BurstController.class)
@AutoConfigureMockMvc(addFilters = false)
class BurstControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @TestConfiguration
    static class MicrometerConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @BeforeEach
    void setUp() {
        Mockito.when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            entity.setUserId(UUID.randomUUID());
            return entity;
        });
    }

    @Test
    void triggerBurst_ReturnsOk() throws Exception {
        mockMvc.perform(post("/test/burst/" + UUID.randomUUID())
                        .param("count", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string("Burst of 100 requests initiated in the background!"));
    }
}

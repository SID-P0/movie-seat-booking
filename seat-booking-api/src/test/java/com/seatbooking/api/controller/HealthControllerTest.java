package com.seatbooking.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSource dataSource;

    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockBean
    private com.seatbooking.api.repository.UserRepository userRepository;

    @Test
    void liveness_ReturnsOk() throws Exception {
        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("alive"));
    }

    @Test
    void readiness_WhenAllHealthy_ReturnsOk() throws Exception {
        Connection dbConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.isValid(anyInt())).thenReturn(true);

        RedisConnection redisConnection = mock(RedisConnection.class);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.postgres").value("ok"))
                .andExpect(jsonPath("$.redis").value("ok"));
    }

    @Test
    void readiness_WhenPostgresDown_ReturnsServiceUnavailable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("DB down"));

        RedisConnection redisConnection = mock(RedisConnection.class);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("not_ready"))
                .andExpect(jsonPath("$.postgres").value("down"))
                .andExpect(jsonPath("$.redis").value("ok"));
    }

    @Test
    void readiness_WhenRedisDown_ReturnsServiceUnavailable() throws Exception {
        Connection dbConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.isValid(anyInt())).thenReturn(true);

        when(redisConnectionFactory.getConnection()).thenThrow(new RuntimeException("Redis down"));

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("not_ready"))
                .andExpect(jsonPath("$.postgres").value("ok"))
                .andExpect(jsonPath("$.redis").value("down"));
    }
}

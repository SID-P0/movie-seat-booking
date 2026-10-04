package com.seatbooking.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.api.repository.UserRepository;
import com.seatbooking.common.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void register_ReturnsCreated() throws Exception {
        UserController.RegisterRequest request = new UserController.RegisterRequest("test@example.com");

        UserEntity savedUser = new UserEntity();
        savedUser.setUserId(UUID.randomUUID());
        savedUser.setEmail(request.getEmail());

        when(userRepository.save(any(UserEntity.class))).thenReturn(savedUser);

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(savedUser.getUserId().toString()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void register_WhenEmailExists_ReturnsConflict() throws Exception {
        UserController.RegisterRequest request = new UserController.RegisterRequest("test@example.com");

        when(userRepository.save(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("Email already exists"));

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email already registered"));
    }
}

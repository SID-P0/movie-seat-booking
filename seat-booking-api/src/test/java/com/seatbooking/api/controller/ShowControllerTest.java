package com.seatbooking.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.api.filter.AuthTokenFilter;
import com.seatbooking.api.service.ShowService;
import com.seatbooking.common.dto.CreateShowRequest;
import com.seatbooking.common.dto.ShowResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShowController.class)
@AutoConfigureMockMvc(addFilters = false) // Disable filters for simple controller testing, or we mock them
class ShowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShowService showService;

    @MockBean
    private com.seatbooking.api.repository.UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private ShowResponse mockShowResponse;
    private UUID showId;

    @BeforeEach
    void setUp() {
        showId = UUID.randomUUID();
        mockShowResponse = new ShowResponse();
        mockShowResponse.setShowId(showId.toString());
        mockShowResponse.setName("Inception");
    }

    @Test
    void createShow_WhenAdmin_ReturnsCreated() throws Exception {
        CreateShowRequest request = new CreateShowRequest();
        request.setName("Inception");
        request.setSeats(List.of("A1", "A2"));
        request.setPrice(1500L);

        when(showService.createShow(any(CreateShowRequest.class))).thenReturn(mockShowResponse);

        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .requestAttr(AuthTokenFilter.ADMIN_ATTR, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.showId").value(showId.toString()))
                .andExpect(jsonPath("$.name").value("Inception"));
    }

    @Test
    void createShow_WhenNotAdmin_ReturnsForbidden() throws Exception {
        CreateShowRequest request = new CreateShowRequest();
        request.setName("Inception");
        request.setSeats(List.of("A1", "A2"));
        request.setPrice(1500L);

        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .requestAttr(AuthTokenFilter.ADMIN_ATTR, false))
                .andExpect(status().isForbidden());
    }

    @Test
    void getShow_ReturnsShow() throws Exception {
        when(showService.getShow(showId)).thenReturn(mockShowResponse);

        mockMvc.perform(get("/shows/" + showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showId").value(showId.toString()))
                .andExpect(jsonPath("$.name").value("Inception"));
    }

    @Test
    void getAllShows_ReturnsList() throws Exception {
        List<Map<String, Object>> shows = List.of(Map.of("showId", showId.toString(), "name", "Inception"));
        when(showService.getAllShows()).thenReturn(shows);

        mockMvc.perform(get("/shows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].showId").value(showId.toString()))
                .andExpect(jsonPath("$[0].name").value("Inception"));
    }
}

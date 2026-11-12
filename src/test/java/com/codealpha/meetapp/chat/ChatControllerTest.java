package com.codealpha.meetapp.chat;

import com.codealpha.meetapp.chat.dto.ChatHistoryResponse;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private ChatService chatService;

    private String validToken;

    @BeforeEach
    void setUp() {
        validToken = jwtService.generateToken(1L, "aarav@example.com");
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/messages without JWT returns 401 Unauthorized")
    void getMessages_unauthorized_returns401() throws Exception {
        mockMvc.perform(get("/api/rooms/DEMO1001/messages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/messages with valid JWT returns 200 and oldest-first history without emails")
    void getMessages_success() throws Exception {
        ChatHistoryResponse item1 = new ChatHistoryResponse(
                101L,
                1L,
                "Aarav Patel",
                "Hello everyone",
                Instant.parse("2026-11-11T10:14:00Z")
        );
        ChatHistoryResponse item2 = new ChatHistoryResponse(
                102L,
                2L,
                "Priya Sharma",
                "Hi Aarav!",
                Instant.parse("2026-11-11T10:14:15Z")
        );

        when(chatService.getHistory(eq("DEMO1001"), eq(1L), eq(50), isNull()))
                .thenReturn(List.of(item1, item2));

        mockMvc.perform(get("/api/rooms/DEMO1001/messages")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(101)))
                .andExpect(jsonPath("$[0].senderId", is(1)))
                .andExpect(jsonPath("$[0].senderName", is("Aarav Patel")))
                .andExpect(jsonPath("$[0].text", is("Hello everyone")))
                .andExpect(jsonPath("$[1].id", is(102)))
                .andExpect(jsonPath("$[1].senderName", is("Priya Sharma")))
                // Strict privacy: ensure email never leaks
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[1].email").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/messages with invalid limit (<1 or >100) returns 400 Bad Request")
    void getMessages_invalidLimit_returns400() throws Exception {
        when(chatService.getHistory(eq("DEMO1001"), eq(1L), eq(105), isNull()))
                .thenThrow(new IllegalArgumentException("limit must be between 1 and 100"));

        mockMvc.perform(get("/api/rooms/DEMO1001/messages?limit=105")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", containsString("limit must be between 1 and 100")));
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/messages with negative before cursor returns 400 Bad Request")
    void getMessages_negativeBeforeCursor_returns400() throws Exception {
        when(chatService.getHistory(eq("DEMO1001"), eq(1L), eq(50), eq(-1L)))
                .thenThrow(new IllegalArgumentException("before must be a positive number"));

        mockMvc.perform(get("/api/rooms/DEMO1001/messages?before=-1")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", containsString("before must be a positive number")));
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/messages by non-participant returns 403 Forbidden")
    void getMessages_outsider_returns403() throws Exception {
        when(chatService.getHistory(eq("DEMO1001"), eq(1L), eq(50), isNull()))
                .thenThrow(new ForbiddenException("User has not participated in room: DEMO1001"));

        mockMvc.perform(get("/api/rooms/DEMO1001/messages")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.message", containsString("User has not participated in room")));
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/messages for non-existent room returns 404 Not Found")
    void getMessages_unknownRoom_returns404() throws Exception {
        when(chatService.getHistory(eq("UNKNOWN1"), eq(1L), eq(50), isNull()))
                .thenThrow(new NotFoundException("Room not found: UNKNOWN1"));

        mockMvc.perform(get("/api/rooms/UNKNOWN1/messages")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Not Found")));
    }
}

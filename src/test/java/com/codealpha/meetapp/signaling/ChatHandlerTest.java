package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.chat.ChatMessage;
import com.codealpha.meetapp.chat.ChatService;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.handler.ChatHandler;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private RoomSessionRegistry registry;
    private RateLimiter rateLimiter;
    private ChatService chatService;
    private ChatHandler chatHandler;

    private WebSocketSession senderSession;
    private WebSocketSession peerSession;

    private User senderUser;
    private Room room;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        registry = new RoomSessionRegistry(objectMapper);
        rateLimiter = new RateLimiter();
        chatHandler = new ChatHandler(registry, chatService, rateLimiter, objectMapper);

        senderUser = new User("Aarav Patel", "aarav@example.com", "hash");
        ReflectionTestUtils.setField(senderUser, "id", 1L);

        room = new Room("DEMO1001", senderUser);
        ReflectionTestUtils.setField(room, "id", 10L);

        senderSession = mockSession("s-sender", 1L, "DEMO1001", "Aarav Patel");
        peerSession = mockSession("s-peer", 2L, "DEMO1001", "Priya Sharma");

        registry.add("DEMO1001", 1L, senderSession);
        registry.add("DEMO1001", 2L, peerSession);
    }

    private WebSocketSession mockSession(String id, Long userId, String roomCode, String name) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put("roomCode", roomCode);
        attrs.put("name", name);
        attrs.put("roomId", 10L);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    @Test
    @DisplayName("valid message: saved to DB and broadcast echo to ALL participants INCLUDING sender")
    void validMessage_savedAndBroadcastToAll() throws Exception {
        ChatMessage saved = new ChatMessage(room, senderUser, "encrypted-data");
        ReflectionTestUtils.setField(saved, "id", 142L);
        saved.setSentAt(Instant.parse("2026-11-11T10:15:30Z"));

        when(chatService.saveMessage("DEMO1001", 1L, "Hello team")).thenReturn(saved);

        SignalMessage clientMsg = new SignalMessage(
                MessageType.CHAT,
                "DEMO1001",
                null,
                null,
                Map.of("text", "Hello team", "clientId", "c-101")
        );

        chatHandler.handle(senderSession, clientMsg);

        // Verify saved before broadcast
        verify(chatService).saveMessage("DEMO1001", 1L, "Hello team");

        // Verify broadcast sent to PEER
        verify(peerSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("\"type\":\"chat\"") &&
                        tm.getPayload().contains("\"id\":142") &&
                        tm.getPayload().contains("\"from\":1") &&
                        tm.getPayload().contains("\"text\":\"Hello team\"") &&
                        tm.getPayload().contains("\"clientId\":\"c-101\"")
        ));

        // Verify broadcast echo ALSO sent to SENDER
        verify(senderSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("\"type\":\"chat\"") &&
                        tm.getPayload().contains("\"id\":142") &&
                        tm.getPayload().contains("\"clientId\":\"c-101\"")
        ));
    }

    @Test
    @DisplayName("server-set fields: id, from, sentAt override any client-supplied spoofed values")
    void serverSetFields_overrideClientValues() throws Exception {
        ChatMessage saved = new ChatMessage(room, senderUser, "encrypted-data");
        ReflectionTestUtils.setField(saved, "id", 500L);
        saved.setSentAt(Instant.parse("2026-11-11T10:20:00Z"));

        when(chatService.saveMessage("DEMO1001", 1L, "Legit text")).thenReturn(saved);

        // Client attempts to spoof from=999, id=99999, name=Hacker
        SignalMessage spoofMsg = new SignalMessage(
                MessageType.CHAT,
                "DEMO1001",
                999L,
                null,
                Map.of("text", "Legit text", "clientId", "c-202", "from", 999, "id", 99999, "name", "Hacker")
        );

        chatHandler.handle(senderSession, spoofMsg);

        verify(peerSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("\"from\":1") &&
                        tm.getPayload().contains("\"name\":\"Aarav Patel\"") &&
                        tm.getPayload().contains("\"id\":500") &&
                        !tm.getPayload().contains("\"from\":999")
        ));
    }

    @Test
    @DisplayName("invalid payload: empty text rejected with INVALID_PAYLOAD to sender only; no broadcast")
    void invalidPayload_emptyText_rejected() throws Exception {
        doThrow(new IllegalArgumentException("Message text cannot be empty"))
                .when(chatService).saveMessage(eq("DEMO1001"), eq(1L), eq("   "));

        SignalMessage emptyMsg = new SignalMessage(
                MessageType.CHAT,
                "DEMO1001",
                null,
                null,
                Map.of("text", "   ", "clientId", "c-303")
        );

        chatHandler.handle(senderSession, emptyMsg);

        verify(senderSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("INVALID_PAYLOAD") &&
                        tm.getPayload().contains("Message text cannot be empty")
        ));

        // Peers receive NOTHING
        verify(peerSession, never()).sendMessage(any());
    }

    @Test
    @DisplayName("non-participant: unregistered session rejected with FORBIDDEN")
    void unregisteredSession_rejectedWithForbidden() throws Exception {
        WebSocketSession outsiderSession = mockSession("s-outsider", 99L, "DEMO1001", "Outsider");
        // Not added to registry

        SignalMessage msg = new SignalMessage(
                MessageType.CHAT,
                "DEMO1001",
                null,
                null,
                Map.of("text", "Sneak attack", "clientId", "c-404")
        );

        chatHandler.handle(outsiderSession, msg);

        verify(outsiderSession).sendMessage(argThat(m ->
                m instanceof TextMessage tm &&
                        tm.getPayload().contains("FORBIDDEN") &&
                        tm.getPayload().contains("You are not an active participant in this room")
        ));

        verify(chatService, never()).saveMessage(any(), any(), any());
    }

    @Test
    @DisplayName("rate limit: sending over 5 messages in 5s triggers RATE_LIMIT_EXCEEDED")
    void rateLimit_burstExceeded_returnsError() throws Exception {
        ChatMessage saved = new ChatMessage(room, senderUser, "enc");
        ReflectionTestUtils.setField(saved, "id", 1L);
        when(chatService.saveMessage(any(), any(), any())).thenReturn(saved);

        SignalMessage msg = new SignalMessage(
                MessageType.CHAT,
                "DEMO1001",
                null,
                null,
                Map.of("text", "Spam", "clientId", "c-505")
        );

        // First 5 pass
        for (int i = 0; i < 5; i++) {
            chatHandler.handle(senderSession, msg);
        }

        // 6th triggers rate limit error
        chatHandler.handle(senderSession, msg);

        verify(senderSession).sendMessage(argThat(m ->
                m instanceof TextMessage tm &&
                        tm.getPayload().contains("RATE_LIMIT_EXCEEDED")
        ));
    }

    @Test
    @DisplayName("repeated rate limit flooding closes socket with code 4029")
    void repeatedRateLimitFlooding_closesSocket() throws Exception {
        ChatMessage saved = new ChatMessage(room, senderUser, "enc");
        ReflectionTestUtils.setField(saved, "id", 1L);
        when(chatService.saveMessage(any(), any(), any())).thenReturn(saved);

        SignalMessage msg = new SignalMessage(
                MessageType.CHAT,
                "DEMO1001",
                null,
                null,
                Map.of("text", "Flood", "clientId", "c-606")
        );

        // Send 10 messages rapidly
        for (int i = 0; i < 10; i++) {
            chatHandler.handle(senderSession, msg);
        }

        // After repeated violations, session.close(4029) is invoked
        verify(senderSession, atLeastOnce()).close(argThat(status ->
                status.getCode() == 4029 && status.getReason().contains("Chat rate limit exceeded")
        ));
    }

    @Test
    @DisplayName("persistence failure: sends MESSAGE_SAVE_FAILED to sender only; aborts broadcast")
    void persistenceFailure_abortsBroadcast() throws Exception {
        when(chatService.saveMessage("DEMO1001", 1L, "Crash message"))
                .thenThrow(new RuntimeException("DB connection pool exhausted"));

        SignalMessage msg = new SignalMessage(
                MessageType.CHAT,
                "DEMO1001",
                null,
                null,
                Map.of("text", "Crash message", "clientId", "c-707")
        );

        chatHandler.handle(senderSession, msg);

        verify(senderSession).sendMessage(argThat(m ->
                m instanceof TextMessage tm &&
                        tm.getPayload().contains("MESSAGE_SAVE_FAILED") &&
                        tm.getPayload().contains("Failed to save message")
        ));

        // Peers receive NOTHING
        verify(peerSession, never()).sendMessage(any());
    }
}

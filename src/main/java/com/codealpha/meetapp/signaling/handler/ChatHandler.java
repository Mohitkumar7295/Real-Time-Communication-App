package com.codealpha.meetapp.signaling.handler;

import com.codealpha.meetapp.chat.ChatMessage;
import com.codealpha.meetapp.chat.ChatService;
import com.codealpha.meetapp.chat.dto.ChatBroadcastPayload;
import com.codealpha.meetapp.chat.dto.ChatSendPayload;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * Handles real-time group chat messages over WebSocket:
 * 1. Checks active room participant membership.
 * 2. Enforces session rate limits (burst 5, steady 1 msg/s, 30/min cap).
 * 3. Persists to database via ChatService BEFORE broadcasting.
 * 4. Broadcasts authoritative echo to ALL room participants including sender.
 * 5. Strictly protects privacy: never logs message text.
 */
@Component
public class ChatHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);

    private static final int BURST_LIMIT_ATTEMPTS = 5;
    private static final int BURST_WINDOW_SECONDS = 5;
    private static final int MINUTE_LIMIT_ATTEMPTS = 30;
    private static final int MINUTE_WINDOW_SECONDS = 60;
    private static final int REPEATED_VIOLATION_THRESHOLD = 3;

    private final RoomSessionRegistry registry;
    private final ChatService chatService;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ChatHandler(RoomSessionRegistry registry,
                       ChatService chatService,
                       RateLimiter rateLimiter,
                       ObjectMapper objectMapper) {
        this.registry = registry;
        this.chatService = chatService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.CHAT;
    }

    @Override
    public void handle(WebSocketSession session, SignalMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        String userName = (String) session.getAttributes().get("name");
        String roomCode = (String) session.getAttributes().get("roomCode");

        if (userId == null || roomCode == null) {
            log.warn("Chat message rejected: session missing userId or roomCode");
            return;
        }

        // 1. Membership check: sender session must be registered to the room
        if (registry.get(roomCode, userId).isEmpty()) {
            sendError(session, roomCode, "FORBIDDEN", "You are not an active participant in this room");
            return;
        }

        // 2. Token-bucket rate limiting (burst 5 in 5s, 30 in 60s)
        String burstKey = "chat:burst:" + session.getId();
        String minKey = "chat:min:" + session.getId();
        boolean allowed = rateLimiter.tryAcquire(burstKey, BURST_LIMIT_ATTEMPTS, BURST_WINDOW_SECONDS)
                && rateLimiter.tryAcquire(minKey, MINUTE_LIMIT_ATTEMPTS, MINUTE_WINDOW_SECONDS);

        if (!allowed) {
            log.warn("Session {} exceeded chat rate limit in room {}", session.getId(), roomCode);
            String violKey = "chat:violation:" + session.getId();
            boolean underViolationThreshold = rateLimiter.tryAcquire(violKey, REPEATED_VIOLATION_THRESHOLD, MINUTE_WINDOW_SECONDS);
            if (!underViolationThreshold) {
                // Repeated flooding: terminate socket with policy violation close code 4029
                try {
                    session.close(new CloseStatus(4029, "Chat rate limit exceeded"));
                } catch (IOException e) {
                    // Ignore during close
                }
                return;
            }

            sendError(session, roomCode, "RATE_LIMIT_EXCEEDED", "Chat rate limit exceeded. Please slow down.");
            return;
        }

        // 3. Parse inbound payload
        ChatSendPayload payload;
        try {
            payload = objectMapper.treeToValue(objectMapper.valueToTree(message.getPayload()), ChatSendPayload.class);
        } catch (Exception e) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Malformed chat payload");
            return;
        }

        if (payload == null || payload.text() == null) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Message text cannot be empty");
            return;
        }

        String rawText = payload.text();
        String clientId = payload.clientId() != null ? payload.clientId().trim() : null;
        if (clientId != null && clientId.length() > 64) {
            clientId = clientId.substring(0, 64);
        }

        // 4. Save BEFORE broadcasting
        ChatMessage savedMessage;
        try {
            savedMessage = chatService.saveMessage(roomCode, userId, rawText);
        } catch (IllegalArgumentException e) {
            sendError(session, roomCode, "INVALID_PAYLOAD", e.getMessage());
            return;
        } catch (ForbiddenException e) {
            sendError(session, roomCode, "FORBIDDEN", e.getMessage());
            return;
        } catch (Exception e) {
            log.error("Failed to save chat message in room {}: {}", roomCode, e.getMessage());
            sendError(session, roomCode, "MESSAGE_SAVE_FAILED", "Failed to save message to chat history");
            return;
        }

        // 5. Broadcast authoritative echo to ALL participants in the room, INCLUDING the sender
        String senderDisplayName = (userName != null && !userName.isBlank())
                ? userName
                : savedMessage.getSender().getName();

        ChatBroadcastPayload broadcastPayload = new ChatBroadcastPayload(
                savedMessage.getId(),
                userId,
                senderDisplayName,
                rawText.trim(),
                savedMessage.getSentAt(),
                clientId
        );

        SignalMessage broadcastEnvelope = new SignalMessage(
                MessageType.CHAT,
                roomCode,
                userId,
                null,
                broadcastPayload
        );

        // excludeUserId = null broadcasts to all sessions in the room including the sender
        registry.broadcast(roomCode, null, broadcastEnvelope);
    }

    private void sendError(WebSocketSession session, String roomCode, String code, String message) {
        if (session != null && session.isOpen()) {
            try {
                SignalMessage error = SignalMessage.error(roomCode, code, message);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            } catch (IOException e) {
                log.warn("Failed to transmit chat error to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}

package com.codealpha.meetapp.signaling.handler;

import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.signaling.dto.PresenterInfo;
import com.codealpha.meetapp.signaling.dto.ScreenShareStartPayload;
import com.codealpha.meetapp.signaling.dto.ScreenShareStopPayload;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Handles screen sharing signaling lifecycle:
 * 1. screen-share-start: enforces atomic single presenter rule per room.
 * 2. screen-share-stop: authorizes presenter self-stop and host override stop.
 * 3. Enforces session rate limits (10/min) and broadcasts notifications.
 */
@Component
public class ScreenShareHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ScreenShareHandler.class);

    private static final int RATE_LIMIT_MAX_ATTEMPTS = 10;
    private static final int RATE_LIMIT_WINDOW_SECONDS = 60;
    private static final int MAX_STREAM_ID_LENGTH = 64;
    private static final int MAX_LABEL_LENGTH = 64;

    private static final Set<String> ALLOWED_CLIENT_REASONS = Set.of(
            "USER_STOPPED",
            "TRACK_ENDED",
            "HOST_STOPPED"
    );

    private final RoomSessionRegistry registry;
    private final RoomRepository roomRepository;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ScreenShareHandler(RoomSessionRegistry registry,
                              RoomRepository roomRepository,
                              RateLimiter rateLimiter,
                              ObjectMapper objectMapper) {
        this.registry = registry;
        this.roomRepository = roomRepository;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.SCREEN_SHARE_START || type == MessageType.SCREEN_SHARE_STOP;
    }

    @Override
    public void handle(WebSocketSession session, SignalMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");

        if (userId == null || roomCode == null) {
            log.warn("Screen share message rejected: missing session attributes");
            return;
        }

        // Rate limit: 10 events per minute per session
        String rateLimitKey = "screenshare:" + session.getId();
        if (!rateLimiter.tryAcquire(rateLimitKey, RATE_LIMIT_MAX_ATTEMPTS, RATE_LIMIT_WINDOW_SECONDS)) {
            log.warn("Session {} exceeded screen share rate limit in room {}", session.getId(), roomCode);
            sendError(session, roomCode, "RATE_LIMIT_EXCEEDED", "Screen share control rate limit exceeded");
            return;
        }

        if (message.getType() == MessageType.SCREEN_SHARE_START) {
            handleStart(session, roomCode, userId, message);
        } else if (message.getType() == MessageType.SCREEN_SHARE_STOP) {
            handleStop(session, roomCode, userId, message);
        }
    }

    private void handleStart(WebSocketSession session, String roomCode, Long userId, SignalMessage message) throws Exception {
        JsonNode payloadNode = objectMapper.valueToTree(message.getPayload());
        if (payloadNode == null || !payloadNode.has("streamId")) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Missing streamId in screen-share-start payload");
            return;
        }

        String streamId = payloadNode.path("streamId").asText("").trim();
        if (streamId.isBlank() || streamId.length() > MAX_STREAM_ID_LENGTH) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "streamId must be 1 to " + MAX_STREAM_ID_LENGTH + " characters");
            return;
        }

        String label = payloadNode.path("label").asText(null);
        if (label != null && label.length() > MAX_LABEL_LENGTH) {
            label = label.substring(0, MAX_LABEL_LENGTH);
        }

        // Enforce atomic single-presenter rule
        boolean acquired = registry.startScreenShare(roomCode, userId, streamId);
        if (!acquired) {
            log.warn("User {} in room {} attempted to start screen share while another participant is active", userId, roomCode);
            sendError(session, roomCode, "ALREADY_PRESENTING", "Another participant is already sharing their screen");
            return;
        }

        log.info("User {} started screen share in room {} (streamId={})", userId, roomCode, streamId);

        // Broadcast screen-share-start to all other peers in the room
        ScreenShareStartPayload broadcastPayload = new ScreenShareStartPayload(userId, streamId, label);
        SignalMessage broadcastMessage = new SignalMessage(
                MessageType.SCREEN_SHARE_START,
                roomCode,
                userId,
                null,
                broadcastPayload
        );
        registry.broadcast(roomCode, userId, broadcastMessage);
    }

    private void handleStop(WebSocketSession session, String roomCode, Long userId, SignalMessage message) throws Exception {
        JsonNode payloadNode = objectMapper.valueToTree(message.getPayload());
        String reason = (payloadNode != null && payloadNode.has("reason"))
                ? payloadNode.path("reason").asText("USER_STOPPED").trim()
                : "USER_STOPPED";

        if (!ALLOWED_CLIENT_REASONS.contains(reason)) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Invalid screen-share-stop reason: " + reason);
            return;
        }

        // Check if requester is room host
        Optional<Room> roomOpt = roomRepository.findByRoomCode(roomCode);
        boolean isHost = roomOpt.isPresent()
                && roomOpt.get().getHost() != null
                && userId.equals(roomOpt.get().getHost().getId());

        if ("HOST_STOPPED".equals(reason) && !isHost) {
            sendError(session, roomCode, "FORBIDDEN", "Only the meeting host can specify HOST_STOPPED");
            return;
        }

        Optional<PresenterInfo> stoppedOpt = registry.stopScreenShare(roomCode, userId, isHost);
        if (stoppedOpt.isEmpty()) {
            Optional<PresenterInfo> currentPresenterOpt = registry.getPresenter(roomCode);
            if (currentPresenterOpt.isPresent() && !currentPresenterOpt.get().userId().equals(userId)) {
                sendError(session, roomCode, "FORBIDDEN", "Only the presenter or the meeting host can stop screen sharing");
                return;
            }
            // No active share in the room
            return;
        }

        Long presenterId = stoppedOpt.get().userId();
        log.info("Screen share stopped in room {} for presenter {} (requester={}, reason={})",
                roomCode, presenterId, userId, reason);

        ScreenShareStopPayload stopPayload = new ScreenShareStopPayload(presenterId, reason);
        SignalMessage stopMessage = new SignalMessage(
                MessageType.SCREEN_SHARE_STOP,
                roomCode,
                userId,
                null,
                stopPayload
        );

        // Broadcast to all other participants in the room (excluding sender).
        // If the host stopped another presenter, the presenter is in the room sessions and receives this broadcast.
        registry.broadcast(roomCode, userId, stopMessage);
    }

    private void sendError(WebSocketSession session, String roomCode, String code, String message) {
        if (session != null && session.isOpen()) {
            try {
                SignalMessage error = SignalMessage.error(roomCode, code, message);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            } catch (IOException e) {
                log.warn("Failed to transmit error to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}

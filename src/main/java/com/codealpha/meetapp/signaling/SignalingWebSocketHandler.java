package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.room.RoomEndedEvent;
import com.codealpha.meetapp.room.RoomParticipant;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.handler.MessageHandler;
import com.codealpha.meetapp.signaling.handler.WebRtcRelayHandler;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main WebSocket signaling handler managing socket lifecycle, envelope routing,
 * rate limiting, presence announcements, and room termination events.
 */
@Component
public class SignalingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingWebSocketHandler.class);

    private static final int MAX_TEXT_PAYLOAD_SIZE = 16 * 1024; // 16 KB

    private final RoomSessionRegistry registry;
    private final List<MessageHandler> messageHandlers;
    private final RoomParticipantRepository roomParticipantRepository;
    private final RateLimiter generalRateLimiter;
    private final WebRtcRelayHandler webRtcRelayHandler;
    private final ObjectMapper objectMapper;

    public SignalingWebSocketHandler(RoomSessionRegistry registry,
                                   List<MessageHandler> messageHandlers,
                                   RoomParticipantRepository roomParticipantRepository,
                                   RateLimiter generalRateLimiter,
                                   WebRtcRelayHandler webRtcRelayHandler,
                                   ObjectMapper objectMapper) {
        this.registry = registry;
        this.messageHandlers = messageHandlers;
        this.roomParticipantRepository = roomParticipantRepository;
        this.generalRateLimiter = generalRateLimiter;
        this.webRtcRelayHandler = webRtcRelayHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");

        if (userId == null || roomCode == null) {
            log.error("Rejecting WebSocket connection: missing session attributes");
            try {
                session.close(new CloseStatus(4001, "Missing session attributes"));
            } catch (IOException ignored) {
            }
            return;
        }

        registry.add(roomCode, userId, session);
        log.info("WebSocket session opened: session={}, user={}, room={}", session.getId(), userId, roomCode);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");

        if (userId == null || roomCode == null) {
            session.close(new CloseStatus(4001, "Unauthorized session"));
            return;
        }

        // 1. Frame Size Enforcement
        if (message.getPayloadLength() > MAX_TEXT_PAYLOAD_SIZE) {
            log.warn("Message from user {} in room {} exceeded maximum size cap ({} bytes)",
                    userId, roomCode, message.getPayloadLength());
            sendError(session, roomCode, "MESSAGE_TOO_LARGE", "Frame payload exceeds 16 KB limit");
            session.close(new CloseStatus(1009, "Message too large"));
            return;
        }

        // 2. Inbound JSON Envelope Parsing
        SignalMessage signalMessage;
        try {
            signalMessage = objectMapper.readValue(message.getPayload(), SignalMessage.class);
        } catch (Exception ex) {
            log.warn("Malformed signaling message from user {} in room {}: {}", userId, roomCode, ex.getMessage());
            sendError(session, roomCode, "INVALID_ENVELOPE", "Malformed JSON signaling envelope");
            return;
        }

        if (signalMessage.getType() == null) {
            sendError(session, roomCode, "MISSING_TYPE", "SignalMessage 'type' is required");
            return;
        }

        // 3. Rate Limiting for non-media messages
        if (!isMediaMessage(signalMessage.getType())) {
            String rateKey = "ws:control:" + session.getId();
            if (!generalRateLimiter.tryAcquire(rateKey, 20, 1)) {
                log.warn("Control message rate limit exceeded for session {} in room {}", session.getId(), roomCode);
                sendError(session, roomCode, "RATE_LIMIT_EXCEEDED", "Too many messages sent. Please slow down.");
                return;
            }
        }

        // 4. Overwrite roomCode and from with verified session attributes
        signalMessage.setRoomCode(roomCode);
        signalMessage.setFrom(userId);

        // 5. Route to Matching MessageHandler
        boolean handled = false;
        for (MessageHandler handler : messageHandlers) {
            if (handler.supports(signalMessage.getType())) {
                handler.handle(session, signalMessage);
                handled = true;
                break;
            }
        }

        if (!handled) {
            log.debug("No active handler for message type {} in room {}", signalMessage.getType(), roomCode);
            sendError(session, roomCode, "NOT_IMPLEMENTED", "Event type " + signalMessage.getType() + " is not supported in this phase");
        }
    }

    @Override
    @Transactional
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");
        Long roomId = (Long) session.getAttributes().get("roomId");

        log.info("WebSocket session closed: session={}, user={}, room={}, code={}, reason={}",
                session.getId(), userId, roomCode, status.getCode(), status.getReason());

        if (userId != null && roomCode != null) {
            // Clean up session rate limiters
            webRtcRelayHandler.cleanupSession(session.getId());
            generalRateLimiter.reset("ws:control:" + session.getId());

            // If closed due to session replacement (code 4000), do NOT broadcast peer-left or mark left in DB
            if (status.getCode() == 4000) {
                log.debug("Session for user {} in room {} closed due to replacement; suppressing peer-left", userId, roomCode);
                return;
            }

            // Remove from registry and evict cached mute state
            registry.remove(roomCode, userId);

            // Update database participant record
            if (roomId != null) {
                Optional<RoomParticipant> participantOpt = roomParticipantRepository
                        .findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);
                participantOpt.ifPresent(p -> {
                    p.setLeftAt(Instant.now());
                    roomParticipantRepository.save(p);
                    log.debug("Marked participant {} as left in room {}", userId, roomId);
                });
            }

            // Broadcast peer-left to remaining participants in the room
            SignalMessage peerLeft = new SignalMessage(
                    MessageType.PEER_LEFT,
                    roomCode,
                    userId,
                    null,
                    Map.of("userId", userId, "reason", "LEFT")
            );
            registry.broadcast(roomCode, userId, peerLeft);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error on WebSocket session {}: {}", session.getId(), exception.getMessage());
        try {
            session.close(new CloseStatus(1011, "Transport error"));
        } catch (IOException ignored) {
        }
    }

    /**
     * Listens to room termination events published by RoomService when a host ends the meeting.
     */
    @EventListener
    public void onRoomEnded(RoomEndedEvent event) {
        log.info("RoomEndedEvent received for room {}. Disconnecting all active peer sessions.", event.getRoomCode());
        SignalMessage notice = new SignalMessage(
                MessageType.ROOM_ENDED,
                event.getRoomCode(),
                event.getHostId(),
                null,
                Map.of("message", "Host ended the meeting")
        );
        registry.closeRoom(event.getRoomCode(), new CloseStatus(4010, "Room ended by host"), notice);
    }

    private boolean isMediaMessage(MessageType type) {
        return type == MessageType.OFFER || type == MessageType.ANSWER || type == MessageType.ICE_CANDIDATE || type == MessageType.DRAW;
    }

    private void sendError(WebSocketSession session, String roomCode, String code, String message) {
        if (session.isOpen()) {
            try {
                SignalMessage error = SignalMessage.error(roomCode, code, message);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            } catch (IOException e) {
                log.warn("Failed to transmit error envelope to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}


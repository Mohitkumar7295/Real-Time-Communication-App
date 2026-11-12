package com.codealpha.meetapp.signaling.handler;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles WebRTC P2P media signaling relays (offer, answer, ice-candidate).
 * Validates payload size limits, enforces rate limits with token bucket (burst 100, 30/s),
 * validates that recipient is in the same room, sets verified 'from', and relays unicast only.
 */
@Component
public class WebRtcRelayHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(WebRtcRelayHandler.class);

    public static final int MAX_SDP_SIZE_BYTES = 16 * 1024;       // 16 KB
    public static final int MAX_CANDIDATE_SIZE_BYTES = 2 * 1024;  // 2 KB

    private final RoomSessionRegistry registry;
    private final ObjectMapper objectMapper;

    // session ID -> TokenBucket for media relay
    private final ConcurrentHashMap<String, TokenBucket> sessionBuckets = new ConcurrentHashMap<>();

    public WebRtcRelayHandler(RoomSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.OFFER || type == MessageType.ANSWER || type == MessageType.ICE_CANDIDATE;
    }

    @Override
    public void handle(WebSocketSession session, SignalMessage message) throws Exception {
        Long senderId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");

        if (senderId == null || roomCode == null) {
            log.warn("Media relay rejected: session missing userId or roomCode attributes");
            return;
        }

        // 1. Rate Limit Check (Token Bucket: capacity 100, steady rate 30/s)
        TokenBucket bucket = sessionBuckets.computeIfAbsent(session.getId(), k -> new TokenBucket(100.0, 30.0));
        if (!bucket.tryConsume()) {
            log.warn("Session {} exceeded media relay rate limit in room {}", session.getId(), roomCode);
            SignalMessage rateError = SignalMessage.error(roomCode, "RATE_LIMIT_EXCEEDED", "Media relay rate limit exceeded");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(rateError)));
            return;
        }

        // 2. Target validation
        Long targetId = message.getTo();
        if (targetId == null) {
            SignalMessage error = SignalMessage.error(roomCode, "INVALID_TARGET", "'to' field is required for media relay");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            return;
        }

        if (targetId.equals(senderId)) {
            // Self-addressed relay is dropped
            return;
        }

        // 3. Payload size validation
        byte[] payloadBytes = objectMapper.writeValueAsBytes(message.getPayload());
        if (message.getType() == MessageType.ICE_CANDIDATE) {
            if (payloadBytes.length > MAX_CANDIDATE_SIZE_BYTES) {
                log.warn("ICE candidate payload from user {} exceeded size cap: {} bytes", senderId, payloadBytes.length);
                SignalMessage error = SignalMessage.error(roomCode, "PAYLOAD_TOO_LARGE", "ICE candidate payload exceeds 2 KB limit");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
                return;
            }
        } else { // OFFER or ANSWER
            if (payloadBytes.length > MAX_SDP_SIZE_BYTES) {
                log.warn("SDP payload from user {} exceeded size cap: {} bytes", senderId, payloadBytes.length);
                SignalMessage error = SignalMessage.error(roomCode, "PAYLOAD_TOO_LARGE", "SDP payload exceeds 16 KB limit");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
                session.close(new CloseStatus(1009, "Message too large"));
                return;
            }
        }

        // 4. Overwrite 'from' with authenticated sender ID
        message.setFrom(senderId);
        message.setRoomCode(roomCode);

        // 5. Unicast forward to target peer in the same room
        boolean delivered = registry.sendTo(roomCode, targetId, message);
        if (!delivered) {
            log.debug("Target peer {} not found in room {} for relay message from {}", targetId, roomCode, senderId);
            SignalMessage targetMissing = SignalMessage.error(roomCode, "TARGET_NOT_FOUND", "Peer is not active in this room");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(targetMissing)));
        }
    }

    /**
     * Cleans up rate limiter bucket when a session terminates.
     */
    public void cleanupSession(String sessionId) {
        sessionBuckets.remove(sessionId);
    }

    /**
     * Token Bucket rate limiter implementation.
     */
    public static class TokenBucket {
        private final double capacity;
        private final double refillRatePerMs;
        private double tokens;
        private long lastRefillMs;

        public TokenBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillRatePerMs = refillPerSecond / 1000.0;
            this.tokens = capacity;
            this.lastRefillMs = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            double refill = (now - lastRefillMs) * refillRatePerMs;
            tokens = Math.min(capacity, tokens + refill);
            lastRefillMs = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        public synchronized double getTokens() {
            return tokens;
        }
    }
}


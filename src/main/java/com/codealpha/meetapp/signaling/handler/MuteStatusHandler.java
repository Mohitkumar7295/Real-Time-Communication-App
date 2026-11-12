package com.codealpha.meetapp.signaling.handler;

import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.signaling.dto.MuteStatusPayload;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Handles 'mute-status' announcements: updates the cached session state in RoomSessionRegistry
 * and broadcasts the updated mute status to all peers in the room.
 */
@Component
public class MuteStatusHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MuteStatusHandler.class);

    private final RoomSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public MuteStatusHandler(RoomSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.MUTE_STATUS;
    }

    @Override
    public void handle(WebSocketSession session, SignalMessage message) throws Exception {
        Long senderId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");

        if (senderId == null || roomCode == null) {
            log.warn("Mute status rejected: session missing userId or roomCode");
            return;
        }

        // Validate and convert payload
        MuteStatusPayload payload;
        try {
            payload = objectMapper.convertValue(message.getPayload(), MuteStatusPayload.class);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid mute-status payload from user {}: {}", senderId, e.getMessage());
            SignalMessage error = SignalMessage.error(roomCode, "INVALID_PAYLOAD", "Invalid mute status format");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            return;
        }

        // 1. Update cached mute status in registry for late joiners
        registry.setMuteStatus(roomCode, senderId, payload.isAudioMuted(), payload.isVideoMuted());

        // 2. Broadcast mute status to all other participants in the room
        SignalMessage broadcastMessage = new SignalMessage(
                MessageType.MUTE_STATUS,
                roomCode,
                senderId,
                null,
                payload
        );
        registry.broadcast(roomCode, senderId, broadcastMessage);

        log.debug("User {} in room {} updated mute status: audioMuted={}, videoMuted={}",
                senderId, roomCode, payload.isAudioMuted(), payload.isVideoMuted());
    }
}


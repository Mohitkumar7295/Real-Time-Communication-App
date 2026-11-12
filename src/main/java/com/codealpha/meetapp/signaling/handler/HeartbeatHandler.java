package com.codealpha.meetapp.signaling.handler;

import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;

/**
 * Handles client heartbeat probes ('ping') and returns immediate 'pong' acknowledgments.
 */
@Component
public class HeartbeatHandler implements MessageHandler {

    private final ObjectMapper objectMapper;

    public HeartbeatHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.PING;
    }

    @Override
    public void handle(WebSocketSession session, SignalMessage message) throws Exception {
        String roomCode = (String) session.getAttributes().get("roomCode");
        Long userId = (Long) session.getAttributes().get("userId");

        SignalMessage pong = new SignalMessage(MessageType.PONG, roomCode, userId, userId, Collections.emptyMap());
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pong)));
    }
}


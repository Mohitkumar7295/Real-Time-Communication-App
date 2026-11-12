package com.codealpha.meetapp.signaling.handler;

import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.signaling.dto.JoinAckPayload;
import com.codealpha.meetapp.signaling.dto.PeerParticipantDto;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.whiteboard.WhiteboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

/**
 * Handles initial 'join' attachment from client, confirms with peer list,
 * and announces arrival via 'peer-joined' broadcast.
 */
@Component
public class JoinHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(JoinHandler.class);

    private final RoomSessionRegistry registry;
    private final WhiteboardService whiteboardService;
    private final ObjectMapper objectMapper;

    public JoinHandler(RoomSessionRegistry registry,
                       WhiteboardService whiteboardService,
                       ObjectMapper objectMapper) {
        this.registry = registry;
        this.whiteboardService = whiteboardService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.JOIN;
    }

    @Override
    public void handle(WebSocketSession session, SignalMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");
        String name = (String) session.getAttributes().getOrDefault("name", "Participant");

        if (userId == null || roomCode == null) {
            log.warn("Join attempted without userId or roomCode in session attributes");
            return;
        }

        // 1. Send join acknowledgment with current active peers and presenter state to the caller
        List<PeerParticipantDto> currentPeers = registry.getPeers(roomCode, userId);
        var presenter = registry.getPresenter(roomCode).orElse(null);
        JoinAckPayload ackPayload = new JoinAckPayload(userId, roomCode, currentPeers, presenter);
        SignalMessage ackMessage = new SignalMessage(MessageType.JOIN, roomCode, userId, userId, ackPayload);

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ackMessage)));

        // 2. Synchronize current whiteboard state to joining participant
        whiteboardService.syncBoardState(session, roomCode, userId);

        // 3. Broadcast 'peer-joined' to all other participants in the room
        SignalMessage peerJoinedMessage = new SignalMessage(
                MessageType.PEER_JOINED,
                roomCode,
                userId,
                null,
                Map.of("userId", userId, "name", name)
        );
        registry.broadcast(roomCode, userId, peerJoinedMessage);

        log.debug("User {} successfully joined room {} with {} peers present", userId, roomCode, currentPeers.size());
    }
}


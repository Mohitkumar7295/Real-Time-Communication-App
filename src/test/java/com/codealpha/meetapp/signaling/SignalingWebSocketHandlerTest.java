package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.room.RoomEndedEvent;
import com.codealpha.meetapp.room.RoomParticipant;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.handler.HeartbeatHandler;
import com.codealpha.meetapp.signaling.handler.JoinHandler;
import com.codealpha.meetapp.signaling.handler.MuteStatusHandler;
import com.codealpha.meetapp.signaling.handler.WebRtcRelayHandler;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.whiteboard.WhiteboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignalingWebSocketHandlerTest {

    private RoomSessionRegistry registry;
    private RoomParticipantRepository roomParticipantRepository;
    private RateLimiter rateLimiter;
    private WebRtcRelayHandler webRtcRelayHandler;
    private SignalingWebSocketHandler signalingHandler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new RoomSessionRegistry(objectMapper);
        roomParticipantRepository = mock(RoomParticipantRepository.class);
        rateLimiter = new RateLimiter();
        webRtcRelayHandler = new WebRtcRelayHandler(registry, objectMapper);

        WhiteboardService whiteboardService = new WhiteboardService(objectMapper);
        JoinHandler joinHandler = new JoinHandler(registry, whiteboardService, objectMapper);
        HeartbeatHandler heartbeatHandler = new HeartbeatHandler(objectMapper);
        MuteStatusHandler muteStatusHandler = new MuteStatusHandler(registry, objectMapper);

        signalingHandler = new SignalingWebSocketHandler(
                registry,
                List.of(joinHandler, heartbeatHandler, webRtcRelayHandler, muteStatusHandler),
                roomParticipantRepository,
                rateLimiter,
                webRtcRelayHandler,
                objectMapper
        );
    }

    private WebSocketSession mockSession(String id, Long userId, String roomCode, String name) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", userId);
        attributes.put("roomCode", roomCode);
        attributes.put("roomId", 100L);
        attributes.put("name", name);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    @DisplayName("join message delivers ack to caller and broadcasts peer-joined to room peers")
    void joinMessage_sendsAckToCaller_andBroadcastsPeerJoined() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");

        signalingHandler.afterConnectionEstablished(s2);
        signalingHandler.afterConnectionEstablished(s1);

        SignalMessage join = new SignalMessage(MessageType.JOIN, "DEMO1001", null, null, Map.of());
        signalingHandler.handleTextMessage(s1, new TextMessage(objectMapper.writeValueAsString(join)));

        // Caller s1 receives join ack with Priya in peers
        verify(s1).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                tm.getPayload().contains("\"type\":\"join\"") &&
                tm.getPayload().contains("Priya")));

        // Peer s2 receives peer-joined broadcast
        verify(s2).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                tm.getPayload().contains("\"type\":\"peer-joined\"") &&
                tm.getPayload().contains("Aarav")));
    }

    @Test
    @DisplayName("socket close broadcasts peer-left and updates DB, unless replaced with code 4000")
    void socketClose_broadcastsPeerLeft_unlessReplaced() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        signalingHandler.afterConnectionEstablished(s1);
        signalingHandler.afterConnectionEstablished(s2);

        RoomParticipant participant = mock(RoomParticipant.class);
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(100L, 1L))
                .thenReturn(Optional.of(participant));

        // 1. Normal close
        signalingHandler.afterConnectionClosed(s1, new CloseStatus(1000, "Normal closure"));

        verify(participant).setLeftAt(any(Instant.class));
        verify(roomParticipantRepository).save(participant);
        verify(s2).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                tm.getPayload().contains("\"type\":\"peer-left\"") &&
                tm.getPayload().contains("\"userId\":1")));
    }

    @Test
    @DisplayName("socket close with code 4000 (replacement) suppresses peer-left broadcast")
    void socketClose_withCode4000_suppressesPeerLeft() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        signalingHandler.afterConnectionEstablished(s1);
        signalingHandler.afterConnectionEstablished(s2);

        // Close due to replacement (code 4000)
        signalingHandler.afterConnectionClosed(s1, new CloseStatus(4000, "Replaced by newer session"));

        // s2 should NEVER receive peer-left because user 1 is still in the room on their new tab
        verify(s2, never()).sendMessage(any(TextMessage.class));
        verify(roomParticipantRepository, never()).save(any(RoomParticipant.class));
    }

    @Test
    @DisplayName("RoomEndedEvent sends room-ended notice and closes all peer sockets with code 4010")
    void onRoomEnded_notifiesAndClosesAllSessions() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        signalingHandler.afterConnectionEstablished(s1);
        signalingHandler.afterConnectionEstablished(s2);

        RoomEndedEvent event = new RoomEndedEvent(this, "DEMO1001", 1L);
        signalingHandler.onRoomEnded(event);

        verify(s1).sendMessage(argThat(msg -> msg instanceof TextMessage tm && tm.getPayload().contains("Host ended the meeting")));
        verify(s1).close(argThat(status -> status.getCode() == 4010));
        verify(s2).sendMessage(argThat(msg -> msg instanceof TextMessage tm && tm.getPayload().contains("Host ended the meeting")));
        verify(s2).close(argThat(status -> status.getCode() == 4010));
    }

    @Test
    @DisplayName("late joiner receives active presenter info in join confirmation")
    void joinMessage_lateJoiner_receivesPresenterInAck() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");

        signalingHandler.afterConnectionEstablished(s2);

        // User 2 starts presenting in the room
        registry.startScreenShare("DEMO1001", 2L, "screen-stream-p2");

        // Late joiner User 1 connects and sends join
        signalingHandler.afterConnectionEstablished(s1);
        SignalMessage join = new SignalMessage(MessageType.JOIN, "DEMO1001", null, null, Map.of());
        signalingHandler.handleTextMessage(s1, new TextMessage(objectMapper.writeValueAsString(join)));

        // User 1 receives join ack containing presenter info
        verify(s1).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("\"presenter\":{") &&
                        tm.getPayload().contains("\"userId\":2") &&
                        tm.getPayload().contains("screen-stream-p2")));
    }
}

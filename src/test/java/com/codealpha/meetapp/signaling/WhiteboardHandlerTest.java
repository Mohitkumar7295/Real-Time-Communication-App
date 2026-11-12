package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.handler.WhiteboardHandler;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.whiteboard.DrawPayload;
import com.codealpha.meetapp.whiteboard.StrokeUndoPayload;
import com.codealpha.meetapp.whiteboard.WhiteboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WhiteboardHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private RoomSessionRegistry registry;
    private WhiteboardService whiteboardService;
    private RoomRepository roomRepository;
    private RateLimiter rateLimiter;
    private WhiteboardHandler handler;

    private WebSocketSession hostSession;
    private WebSocketSession guestSession;
    private User hostUser;
    private User guestUser;
    private Room room;

    @BeforeEach
    void setUp() {
        registry = new RoomSessionRegistry(objectMapper);
        whiteboardService = new WhiteboardService(objectMapper);
        roomRepository = mock(RoomRepository.class);
        rateLimiter = new RateLimiter();

        handler = new WhiteboardHandler(registry, whiteboardService, roomRepository, rateLimiter, objectMapper);

        hostUser = new User("Host User", "host@example.com", "hash");
        ReflectionTestUtils.setField(hostUser, "id", 1L);

        guestUser = new User("Guest User", "guest@example.com", "hash");
        ReflectionTestUtils.setField(guestUser, "id", 2L);

        room = new Room("DEMO1001", hostUser);
        ReflectionTestUtils.setField(room, "id", 100L);
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(room));

        hostSession = mockSession("s-host", 1L, "DEMO1001", "Host User");
        guestSession = mockSession("s-guest", 2L, "DEMO1001", "Guest User");

        registry.add("DEMO1001", 1L, hostSession);
        registry.add("DEMO1001", 2L, guestSession);
    }

    private WebSocketSession mockSession(String id, Long userId, String roomCode, String name) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put("roomCode", roomCode);
        attrs.put("name", name);
        attrs.put("roomId", 100L);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    @Test
    @DisplayName("valid DRAW broadcasts to other participants excluding sender")
    void validDraw_broadcastsToPeers() throws Exception {
        DrawPayload payload = new DrawPayload(
                "s_test_1",
                "pen",
                "#1F6F5B",
                3.0,
                List.of(new double[]{0.2, 0.3}, new double[]{0.25, 0.35}),
                false
        );

        SignalMessage msg = new SignalMessage(MessageType.DRAW, "DEMO1001", 1L, null, payload);
        handler.handle(hostSession, msg);

        // Verify guest received the DRAW event
        ArgumentCaptor<TextMessage> guestCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(guestSession, times(1)).sendMessage(guestCaptor.capture());

        SignalMessage delivered = objectMapper.readValue(guestCaptor.getValue().getPayload(), SignalMessage.class);
        assertEquals("draw", delivered.getType().getValue());
        assertEquals(1L, delivered.getFrom());

        // Verify host (sender) did NOT receive the broadcast
        verify(hostSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("DRAW with invalid tool sends INVALID_PAYLOAD error")
    void draw_invalidTool_sendsError() throws Exception {
        DrawPayload payload = new DrawPayload(
                "s_bad",
                "crayon", // invalid
                "#1F6F5B",
                3.0,
                List.of(new double[]{0.2, 0.3}),
                false
        );

        SignalMessage msg = new SignalMessage(MessageType.DRAW, "DEMO1001", 1L, null, payload);
        handler.handle(hostSession, msg);

        ArgumentCaptor<TextMessage> hostCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(hostSession, times(1)).sendMessage(hostCaptor.capture());

        SignalMessage errorMsg = objectMapper.readValue(hostCaptor.getValue().getPayload(), SignalMessage.class);
        assertEquals("error", errorMsg.getType().getValue());
        Map<?, ?> errPayload = (Map<?, ?>) errorMsg.getPayload();
        assertEquals("INVALID_PAYLOAD", errPayload.get("code"));
    }

    @Test
    @DisplayName("DRAW with coordinates outside [0.0, 1.0] sends INVALID_PAYLOAD error")
    void draw_outOfBounds_sendsError() throws Exception {
        DrawPayload payload = new DrawPayload(
                "s_bad_coords",
                "pen",
                "#1F6F5B",
                3.0,
                List.of(new double[]{1.25, 0.3}), // x > 1.0
                false
        );

        SignalMessage msg = new SignalMessage(MessageType.DRAW, "DEMO1001", 1L, null, payload);
        handler.handle(hostSession, msg);

        ArgumentCaptor<TextMessage> hostCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(hostSession, times(1)).sendMessage(hostCaptor.capture());

        SignalMessage errorMsg = objectMapper.readValue(hostCaptor.getValue().getPayload(), SignalMessage.class);
        assertEquals("error", errorMsg.getType().getValue());
        Map<?, ?> errPayload = (Map<?, ?>) errorMsg.getPayload();
        assertEquals("INVALID_PAYLOAD", errPayload.get("code"));
    }

    @Test
    @DisplayName("STROKE_UNDO broadcasts to all participants when owned by caller")
    void strokeUndo_success_broadcastsToAll() throws Exception {
        // First draw a stroke by host
        DrawPayload p = new DrawPayload("s_undo_1", "pen", "#1F6F5B", 2.0, List.of(new double[]{0.1, 0.1}), true);
        whiteboardService.appendStrokeSegment("DEMO1001", 1L, p);

        // Host undoes own stroke
        StrokeUndoPayload undoPayload = new StrokeUndoPayload("s_undo_1");
        SignalMessage msg = new SignalMessage(MessageType.STROKE_UNDO, "DEMO1001", 1L, null, undoPayload);
        handler.handle(hostSession, msg);

        // Broadcast should go to both host and guest
        verify(hostSession, times(1)).sendMessage(any(TextMessage.class));
        verify(guestSession, times(1)).sendMessage(any(TextMessage.class));

        // State is empty
        assertEquals(0, whiteboardService.getStrokes("DEMO1001").size());
    }

    @Test
    @DisplayName("STROKE_UNDO throws STROKE_NOT_OWNED when guest tries to undo host stroke")
    void strokeUndo_notOwner_sendsError() throws Exception {
        DrawPayload p = new DrawPayload("s_host_stroke", "pen", "#1F6F5B", 2.0, List.of(new double[]{0.1, 0.1}), true);
        whiteboardService.appendStrokeSegment("DEMO1001", 1L, p);

        // Guest attempts to undo host's stroke
        StrokeUndoPayload undoPayload = new StrokeUndoPayload("s_host_stroke");
        SignalMessage msg = new SignalMessage(MessageType.STROKE_UNDO, "DEMO1001", 2L, null, undoPayload);
        handler.handle(guestSession, msg);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(guestSession, times(1)).sendMessage(captor.capture());

        SignalMessage errorMsg = objectMapper.readValue(captor.getValue().getPayload(), SignalMessage.class);
        assertEquals("error", errorMsg.getType().getValue());
        Map<?, ?> errPayload = (Map<?, ?>) errorMsg.getPayload();
        assertEquals("STROKE_NOT_OWNED", errPayload.get("code"));
    }

    @Test
    @DisplayName("CLEAR_BOARD succeeds when invoked by host and broadcasts to all")
    void clearBoard_host_broadcastsToAll() throws Exception {
        whiteboardService.appendStrokeSegment("DEMO1001", 1L,
                new DrawPayload("s_1", "pen", "#1F6F5B", 2.0, List.of(new double[]{0.1, 0.1}), true));

        SignalMessage msg = new SignalMessage(MessageType.CLEAR_BOARD, "DEMO1001", 1L, null, Map.of());
        handler.handle(hostSession, msg);

        verify(hostSession, times(1)).sendMessage(any(TextMessage.class));
        verify(guestSession, times(1)).sendMessage(any(TextMessage.class));
        assertEquals(0, whiteboardService.getStrokes("DEMO1001").size());
    }

    @Test
    @DisplayName("CLEAR_BOARD rejects guest attempt with HOST_ONLY_ACTION")
    void clearBoard_guest_rejectsWithHostOnlyAction() throws Exception {
        SignalMessage msg = new SignalMessage(MessageType.CLEAR_BOARD, "DEMO1001", 2L, null, Map.of());
        handler.handle(guestSession, msg);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(guestSession, times(1)).sendMessage(captor.capture());

        SignalMessage errorMsg = objectMapper.readValue(captor.getValue().getPayload(), SignalMessage.class);
        assertEquals("error", errorMsg.getType().getValue());
        Map<?, ?> errPayload = (Map<?, ?>) errorMsg.getPayload();
        assertEquals("HOST_ONLY_ACTION", errPayload.get("code"));
    }
}

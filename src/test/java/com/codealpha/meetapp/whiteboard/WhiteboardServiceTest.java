package com.codealpha.meetapp.whiteboard;

import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WhiteboardServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private WhiteboardService service;

    @BeforeEach
    void setUp() {
        service = new WhiteboardService(objectMapper);
    }

    @Test
    @DisplayName("appendStrokeSegment creates a new stroke and accumulates points")
    void appendStrokeSegment_createsAndAccumulates() {
        DrawPayload p1 = new DrawPayload(
                "s_1001",
                "pen",
                "#1F6F5B",
                4.0,
                List.of(new double[]{0.1, 0.2}, new double[]{0.15, 0.25}),
                false
        );

        service.appendStrokeSegment("ROOM1", 1L, p1);

        List<WhiteboardStroke> strokes = service.getStrokes("ROOM1");
        assertEquals(1, strokes.size());
        WhiteboardStroke stroke = strokes.get(0);
        assertEquals("s_1001", stroke.getStrokeId());
        assertEquals(1L, stroke.getUserId());
        assertEquals("pen", stroke.getTool());
        assertEquals("#1F6F5B", stroke.getColor());
        assertEquals(2, stroke.getPoints().size());

        // Append more points to existing stroke
        DrawPayload p2 = new DrawPayload(
                "s_1001",
                "pen",
                "#1F6F5B",
                4.0,
                List.of(new double[]{0.2, 0.3}),
                true
        );

        service.appendStrokeSegment("ROOM1", 1L, p2);

        strokes = service.getStrokes("ROOM1");
        assertEquals(1, strokes.size());
        assertEquals(3, strokes.get(0).getPoints().size());
    }

    @Test
    @DisplayName("appendStrokeSegment rejects conflicting owner with STROKE_CONFLICT")
    void appendStrokeSegment_conflictOwner_throws() {
        DrawPayload p1 = new DrawPayload("s_1001", "pen", "#1F6F5B", 4.0, List.of(new double[]{0.1, 0.2}), false);
        service.appendStrokeSegment("ROOM1", 1L, p1);

        DrawPayload p2 = new DrawPayload("s_1001", "pen", "#1F6F5B", 4.0, List.of(new double[]{0.2, 0.3}), true);
        WhiteboardException ex = assertThrows(WhiteboardException.class, () ->
                service.appendStrokeSegment("ROOM1", 2L, p2)
        );

        assertEquals("STROKE_CONFLICT", ex.getErrorCode());
    }

    @Test
    @DisplayName("undoStroke removes stroke owned by user")
    void undoStroke_successForOwner() {
        DrawPayload p1 = new DrawPayload("s_1001", "pen", "#1F6F5B", 4.0, List.of(new double[]{0.1, 0.2}), true);
        service.appendStrokeSegment("ROOM1", 1L, p1);

        assertEquals(1, service.getStrokes("ROOM1").size());

        boolean undone = service.undoStroke("ROOM1", 1L, "s_1001");
        assertTrue(undone);
        assertEquals(0, service.getStrokes("ROOM1").size());
    }

    @Test
    @DisplayName("undoStroke throws STROKE_NOT_OWNED if another participant attempts undo")
    void undoStroke_notOwner_throws() {
        DrawPayload p1 = new DrawPayload("s_1001", "pen", "#1F6F5B", 4.0, List.of(new double[]{0.1, 0.2}), true);
        service.appendStrokeSegment("ROOM1", 1L, p1);

        WhiteboardException ex = assertThrows(WhiteboardException.class, () ->
                service.undoStroke("ROOM1", 2L, "s_1001")
        );

        assertEquals("STROKE_NOT_OWNED", ex.getErrorCode());
        assertEquals(1, service.getStrokes("ROOM1").size());
    }

    @Test
    @DisplayName("clearBoard wipes all strokes and total points")
    void clearBoard_wipesAll() {
        service.appendStrokeSegment("ROOM1", 1L, new DrawPayload("s_1", "pen", "#1F6F5B", 2.0, List.of(new double[]{0.1, 0.1}), true));
        service.appendStrokeSegment("ROOM1", 2L, new DrawPayload("s_2", "line", "#E74C3C", 3.0, List.of(new double[]{0.2, 0.2}, new double[]{0.4, 0.4}), true));

        assertEquals(2, service.getStrokes("ROOM1").size());

        service.clearBoard("ROOM1");
        assertEquals(0, service.getStrokes("ROOM1").size());
    }

    @Test
    @DisplayName("syncBoardState transmits empty chunk when board has no strokes")
    void syncBoardState_emptyBoard() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        service.syncBoardState(session, "ROOM_EMPTY", 2L);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(captor.capture());

        SignalMessage msg = objectMapper.readValue(captor.getValue().getPayload(), SignalMessage.class);
        assertEquals("board-state", msg.getType().getValue());
        BoardStatePayload payload = objectMapper.convertValue(msg.getPayload(), BoardStatePayload.class);
        assertEquals(0, payload.getChunkIndex());
        assertEquals(1, payload.getTotalChunks());
        assertTrue(payload.getStrokes().isEmpty());
    }

    @Test
    @DisplayName("syncBoardState splits strokes into chunks when exceeding chunk threshold")
    void syncBoardState_chunkedStrokes() throws Exception {
        // Add 30 strokes (exceeds CHUNK_STROKE_LIMIT = 25)
        for (int i = 0; i < 30; i++) {
            service.appendStrokeSegment("ROOM_BUSY", 1L,
                    new DrawPayload("s_" + i, "pen", "#1F6F5B", 2.0, List.of(new double[]{0.1, 0.1}), true));
        }

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        service.syncBoardState(session, "ROOM_BUSY", 2L);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(captor.capture());

        List<TextMessage> sent = captor.getAllValues();
        assertEquals(2, sent.size());

        SignalMessage msg1 = objectMapper.readValue(sent.get(0).getPayload(), SignalMessage.class);
        BoardStatePayload p1 = objectMapper.convertValue(msg1.getPayload(), BoardStatePayload.class);
        assertEquals(0, p1.getChunkIndex());
        assertEquals(2, p1.getTotalChunks());
        assertEquals(25, p1.getStrokes().size());

        SignalMessage msg2 = objectMapper.readValue(sent.get(1).getPayload(), SignalMessage.class);
        BoardStatePayload p2 = objectMapper.convertValue(msg2.getPayload(), BoardStatePayload.class);
        assertEquals(1, p2.getChunkIndex());
        assertEquals(2, p2.getTotalChunks());
        assertEquals(5, p2.getStrokes().size());
    }

    @Test
    @DisplayName("exceeding max points per stroke throws INVALID_PAYLOAD")
    void maxPointsPerStroke_exceeded_throws() {
        List<double[]> largePoints = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            largePoints.add(new double[]{0.1, 0.1});
        }

        DrawPayload payload = new DrawPayload("s_big", "pen", "#1F6F5B", 3.0, largePoints, true);
        WhiteboardException ex = assertThrows(WhiteboardException.class, () ->
                service.appendStrokeSegment("ROOM1", 1L, payload)
        );

        assertEquals("INVALID_PAYLOAD", ex.getErrorCode());
    }
}

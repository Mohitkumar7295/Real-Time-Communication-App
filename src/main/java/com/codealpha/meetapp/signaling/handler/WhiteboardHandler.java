package com.codealpha.meetapp.signaling.handler;

import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.whiteboard.DrawPayload;
import com.codealpha.meetapp.whiteboard.StrokeUndoPayload;
import com.codealpha.meetapp.whiteboard.WhiteboardException;
import com.codealpha.meetapp.whiteboard.WhiteboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Handles real-time whiteboard signaling events:
 * 1. 'draw' stroke streaming and shape rendering.
 * 2. 'stroke-undo' user-owned stroke eviction.
 * 3. 'clear-board' host-only board wipe.
 */
@Component
public class WhiteboardHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardHandler.class);

    private static final Set<String> ALLOWED_TOOLS = Set.of("pen", "eraser", "line", "rect", "ellipse");
    private static final Set<String> SHAPE_TOOLS = Set.of("line", "rect", "ellipse");
    private static final String COLOR_PATTERN = "^#[0-9a-fA-F]{6}$";
    private static final String STROKE_ID_PATTERN = "^[a-zA-Z0-9_-]{1,64}$";

    private final RoomSessionRegistry registry;
    private final WhiteboardService whiteboardService;
    private final RoomRepository roomRepository;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public WhiteboardHandler(RoomSessionRegistry registry,
                             WhiteboardService whiteboardService,
                             RoomRepository roomRepository,
                             RateLimiter rateLimiter,
                             ObjectMapper objectMapper) {
        this.registry = registry;
        this.whiteboardService = whiteboardService;
        this.roomRepository = roomRepository;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.DRAW
                || type == MessageType.STROKE_UNDO
                || type == MessageType.CLEAR_BOARD;
    }

    @Override
    public void handle(WebSocketSession session, SignalMessage message) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        String roomCode = (String) session.getAttributes().get("roomCode");

        if (userId == null || roomCode == null) {
            log.warn("Whiteboard event rejected: session missing userId or roomCode");
            return;
        }

        // 1. Membership check: session must be in room
        if (registry.get(roomCode, userId).isEmpty()) {
            sendError(session, roomCode, "NOT_IN_ROOM", "You are not an active participant in this room");
            return;
        }

        MessageType type = message.getType();
        if (type == MessageType.DRAW) {
            handleDraw(session, roomCode, userId, message);
        } else if (type == MessageType.STROKE_UNDO) {
            handleUndo(session, roomCode, userId, message);
        } else if (type == MessageType.CLEAR_BOARD) {
            handleClear(session, roomCode, userId, message);
        }
    }

    private void handleDraw(WebSocketSession session, String roomCode, Long userId, SignalMessage message) {
        // Token bucket rate limiting: 40 burst, 20/sec steady
        String rateKey = "draw:rate:" + session.getId();
        if (!rateLimiter.tryAcquire(rateKey, 40, 1)) {
            sendError(session, roomCode, "RATE_LIMITED", "Drawing rate limit exceeded. Please slow down.");
            return;
        }

        DrawPayload payload;
        try {
            payload = objectMapper.convertValue(message.getPayload(), DrawPayload.class);
        } catch (IllegalArgumentException e) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Malformed draw payload");
            return;
        }

        if (payload == null) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Missing draw payload");
            return;
        }

        // Payload validation
        String strokeId = payload.getStrokeId();
        if (strokeId == null || !strokeId.matches(STROKE_ID_PATTERN)) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Invalid strokeId format");
            return;
        }

        String tool = payload.getTool();
        if (tool == null || !ALLOWED_TOOLS.contains(tool)) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Invalid tool specified");
            return;
        }

        String color = payload.getColor();
        if (color == null || !color.matches(COLOR_PATTERN)) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Invalid hex color format");
            return;
        }

        Double width = payload.getWidth();
        if (width == null || width < 1.0 || width > 32.0 || Double.isNaN(width) || Double.isInfinite(width)) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Stroke width must be between 1.0 and 32.0");
            return;
        }

        List<double[]> points = payload.getPoints();
        if (points == null || points.isEmpty() || points.size() > 50) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Points batch must contain between 1 and 50 points");
            return;
        }

        for (double[] pt : points) {
            if (pt == null || pt.length != 2) {
                sendError(session, roomCode, "INVALID_PAYLOAD", "Each point must be a 2D [x, y] coordinate");
                return;
            }
            double x = pt[0];
            double y = pt[1];
            if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y) || x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
                sendError(session, roomCode, "INVALID_PAYLOAD", "Coordinates must be normalized between 0.0 and 1.0");
                return;
            }
        }

        if (SHAPE_TOOLS.contains(tool)) {
            if (points.size() != 2 || !Boolean.TRUE.equals(payload.getIsFinal())) {
                sendError(session, roomCode, "INVALID_PAYLOAD", "Shape tools require exactly 2 points and final=true");
                return;
            }
        }

        try {
            whiteboardService.appendStrokeSegment(roomCode, userId, payload);
        } catch (WhiteboardException e) {
            sendError(session, roomCode, e.getErrorCode(), e.getMessage());
            return;
        }

        // Broadcast to other participants in the room (sender excluded)
        SignalMessage broadcastMessage = new SignalMessage(MessageType.DRAW, roomCode, userId, null, payload);
        registry.broadcast(roomCode, userId, broadcastMessage);
    }

    private void handleUndo(WebSocketSession session, String roomCode, Long userId, SignalMessage message) {
        StrokeUndoPayload payload;
        try {
            payload = objectMapper.convertValue(message.getPayload(), StrokeUndoPayload.class);
        } catch (IllegalArgumentException e) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Malformed stroke-undo payload");
            return;
        }

        if (payload == null || payload.getStrokeId() == null || payload.getStrokeId().isBlank()) {
            sendError(session, roomCode, "INVALID_PAYLOAD", "Missing strokeId for undo");
            return;
        }

        try {
            boolean removed = whiteboardService.undoStroke(roomCode, userId, payload.getStrokeId());
            if (!removed) {
                sendError(session, roomCode, "INVALID_PAYLOAD", "Stroke not found on board");
                return;
            }
        } catch (WhiteboardException e) {
            sendError(session, roomCode, e.getErrorCode(), e.getMessage());
            return;
        }

        // Broadcast stroke-undo to all participants in the room
        SignalMessage broadcastMessage = new SignalMessage(MessageType.STROKE_UNDO, roomCode, userId, null, payload);
        registry.broadcast(roomCode, null, broadcastMessage);
    }

    private void handleClear(WebSocketSession session, String roomCode, Long userId, SignalMessage message) {
        Optional<Room> roomOpt = roomRepository.findByRoomCode(roomCode);
        if (roomOpt.isEmpty()) {
            sendError(session, roomCode, "ROOM_NOT_FOUND", "Room not found");
            return;
        }

        Room room = roomOpt.get();
        if (room.getHost() == null || !room.getHost().getId().equals(userId)) {
            sendError(session, roomCode, "HOST_ONLY_ACTION", "Only the meeting host can clear the whiteboard");
            return;
        }

        whiteboardService.clearBoard(roomCode);

        // Broadcast clear-board to all participants in the room
        SignalMessage broadcastMessage = new SignalMessage(MessageType.CLEAR_BOARD, roomCode, userId, null, message.getPayload());
        registry.broadcast(roomCode, null, broadcastMessage);
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

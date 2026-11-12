package com.codealpha.meetapp.whiteboard;

import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages in-memory collaborative whiteboard state per room:
 * - Real-time stroke point accumulation and geometric shapes.
 * - Ownership-enforced stroke undo.
 * - Host-only board clearing.
 * - Chunked board state transmission to late joiners.
 * - Enforces room limits (1,000 strokes, 500 pts/stroke, 25,000 pts/board).
 */
@Service
public class WhiteboardService {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardService.class);

    public static final int MAX_STROKES_PER_ROOM = 1_000;
    public static final int MAX_POINTS_PER_STROKE = 500;
    public static final int MAX_TOTAL_POINTS = 25_000;
    public static final int CHUNK_STROKE_LIMIT = 25;

    public static class RoomBoard {
        private final List<WhiteboardStroke> strokes = new ArrayList<>();
        private final Map<String, WhiteboardStroke> strokeIndex = new HashMap<>();
        private final AtomicInteger totalPoints = new AtomicInteger(0);

        public synchronized List<WhiteboardStroke> getStrokesCopy() {
            return new ArrayList<>(strokes);
        }

        public synchronized int getStrokeCount() {
            return strokes.size();
        }

        public synchronized int getTotalPoints() {
            return totalPoints.get();
        }
    }

    private final ConcurrentHashMap<String, RoomBoard> boards = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public WhiteboardService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private RoomBoard getOrCreateBoard(String roomCode) {
        return boards.computeIfAbsent(roomCode, k -> new RoomBoard());
    }

    /**
     * Appends drawing points to an active stroke or creates a new stroke.
     */
    public void appendStrokeSegment(String roomCode, Long userId, DrawPayload payload) {
        RoomBoard board = getOrCreateBoard(roomCode);
        String strokeId = payload.getStrokeId();
        List<double[]> points = payload.getPoints() != null ? payload.getPoints() : Collections.emptyList();
        int addedPoints = points.size();

        synchronized (board) {
            WhiteboardStroke existing = board.strokeIndex.get(strokeId);

            if (existing != null) {
                // Stroke already exists: check ownership
                if (!existing.getUserId().equals(userId)) {
                    throw new WhiteboardException("STROKE_CONFLICT", "Stroke owned by another participant");
                }

                // Check per-stroke limit
                if (existing.getPoints().size() + addedPoints > MAX_POINTS_PER_STROKE) {
                    throw new WhiteboardException("INVALID_PAYLOAD", "Max points per stroke (" + MAX_POINTS_PER_STROKE + ") exceeded");
                }

                // Check total room points limit
                if (board.totalPoints.get() + addedPoints > MAX_TOTAL_POINTS) {
                    throw new WhiteboardException("BOARD_FULL", "Whiteboard capacity reached for this room");
                }

                existing.appendPoints(points, Boolean.TRUE.equals(payload.getIsFinal()));
                board.totalPoints.addAndGet(addedPoints);
            } else {
                // New stroke: check total strokes limit
                if (board.strokes.size() >= MAX_STROKES_PER_ROOM) {
                    throw new WhiteboardException("BOARD_FULL", "Whiteboard capacity reached for this room");
                }

                // Check per-stroke limit
                if (addedPoints > MAX_POINTS_PER_STROKE) {
                    throw new WhiteboardException("INVALID_PAYLOAD", "Max points per stroke (" + MAX_POINTS_PER_STROKE + ") exceeded");
                }

                // Check total room points limit
                if (board.totalPoints.get() + addedPoints > MAX_TOTAL_POINTS) {
                    throw new WhiteboardException("BOARD_FULL", "Whiteboard capacity reached for this room");
                }

                WhiteboardStroke newStroke = new WhiteboardStroke(
                        strokeId,
                        userId,
                        payload.getTool(),
                        payload.getColor(),
                        payload.getWidth() != null ? payload.getWidth() : 3.0,
                        points,
                        Boolean.TRUE.equals(payload.getIsFinal())
                );

                board.strokes.add(newStroke);
                board.strokeIndex.put(strokeId, newStroke);
                board.totalPoints.addAndGet(addedPoints);
            }
        }
    }

    /**
     * Undoes a stroke owned by the caller.
     */
    public boolean undoStroke(String roomCode, Long userId, String strokeId) {
        RoomBoard board = boards.get(roomCode);
        if (board == null) return false;

        synchronized (board) {
            WhiteboardStroke stroke = board.strokeIndex.get(strokeId);
            if (stroke == null) {
                return false;
            }

            if (!stroke.getUserId().equals(userId)) {
                throw new WhiteboardException("STROKE_NOT_OWNED", "You can only undo your own strokes");
            }

            board.strokes.remove(stroke);
            board.strokeIndex.remove(strokeId);
            board.totalPoints.addAndGet(-stroke.getPoints().size());
            return true;
        }
    }

    /**
     * Clears all strokes on the whiteboard.
     */
    public void clearBoard(String roomCode) {
        RoomBoard board = boards.get(roomCode);
        if (board != null) {
            synchronized (board) {
                board.strokes.clear();
                board.strokeIndex.clear();
                board.totalPoints.set(0);
            }
        }
    }

    /**
     * Retrieves a copy of the current strokes for a room.
     */
    public List<WhiteboardStroke> getStrokes(String roomCode) {
        RoomBoard board = boards.get(roomCode);
        if (board == null) return Collections.emptyList();
        return board.getStrokesCopy();
    }

    /**
     * Synchronizes chunked board state to a client session.
     */
    public void syncBoardState(WebSocketSession session, String roomCode, Long targetUserId) {
        if (session == null || !session.isOpen()) return;

        List<WhiteboardStroke> allStrokes = getStrokes(roomCode);

        try {
            if (allStrokes.isEmpty()) {
                BoardStatePayload emptyPayload = new BoardStatePayload(0, 1, Collections.emptyList());
                SignalMessage msg = new SignalMessage(MessageType.BOARD_STATE, roomCode, null, targetUserId, emptyPayload);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
                return;
            }

            List<List<WhiteboardStroke>> chunks = partition(allStrokes, CHUNK_STROKE_LIMIT);
            int totalChunks = chunks.size();

            for (int i = 0; i < totalChunks; i++) {
                BoardStatePayload chunkPayload = new BoardStatePayload(i, totalChunks, chunks.get(i));
                SignalMessage msg = new SignalMessage(MessageType.BOARD_STATE, roomCode, null, targetUserId, chunkPayload);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            }
        } catch (IOException e) {
            log.warn("Failed to transmit board state chunk to user {} in room {}: {}", targetUserId, roomCode, e.getMessage());
        }
    }

    /**
     * Cleans up room whiteboard state when room is deleted or ended.
     */
    public void clearRoom(String roomCode) {
        boards.remove(roomCode);
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(new ArrayList<>(list.subList(i, Math.min(list.size(), i + size))));
        }
        return parts;
    }
}

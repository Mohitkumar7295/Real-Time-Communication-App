package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.signaling.dto.MuteStatusPayload;
import com.codealpha.meetapp.signaling.dto.PeerParticipantDto;
import com.codealpha.meetapp.signaling.dto.PresenterInfo;
import com.codealpha.meetapp.signaling.dto.ScreenShareStopPayload;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory registry maintaining active WebSocket sessions and their cached mute states.
 * Wraps sessions with ConcurrentWebSocketSessionDecorator to guarantee thread-safe serialized sends.
 */
@Component
public class RoomSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RoomSessionRegistry.class);

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024; // 64 KB

    // roomCode -> (userId -> WebSocketSession)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    // roomCode -> (userId -> MuteStatusPayload)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, MuteStatusPayload>> roomMuteStates = new ConcurrentHashMap<>();

    // roomCode -> PresenterInfo (active screen presenter per room)
    private final ConcurrentHashMap<String, PresenterInfo> roomPresenters = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public RoomSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Registers a new session for a user in a room.
     * If an active session already exists for the same user in that room, it is closed with code 4000 (Replaced)
     * and its cached mute state is evicted.
     */
    public WebSocketSession add(String roomCode, Long userId, WebSocketSession rawSession) {
        if (roomCode == null || userId == null || rawSession == null) {
            throw new IllegalArgumentException("roomCode, userId, and session must not be null");
        }

        ConcurrentHashMap<Long, WebSocketSession> userMap =
                roomSessions.computeIfAbsent(roomCode, k -> new ConcurrentHashMap<>());

        WebSocketSession decoratedSession = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES
        );

        WebSocketSession previousSession = userMap.put(userId, decoratedSession);
        if (previousSession != null && previousSession.isOpen()) {
            log.info("Closing previous session for user {} in room {} (replaced by newer session)", userId, roomCode);
            try {
                previousSession.close(new CloseStatus(4000, "Replaced by newer session"));
            } catch (IOException e) {
                log.warn("Error closing replaced session for user {}: {}", userId, e.getMessage());
            }
        }

        // Clean any stale cached mute state upon replacement
        removeMuteStatus(roomCode, userId);

        // If user was presenting in this room, replacement/reconnect clears presenter state
        Optional<PresenterInfo> prevPresenter = clearPresenterIfPresent(roomCode, userId);
        if (prevPresenter.isPresent()) {
            broadcastScreenShareStop(roomCode, userId, "DISCONNECTED");
        }

        return decoratedSession;
    }

    /**
     * Removes a session and strictly removes its cached mute status from the registry.
     */
    public WebSocketSession remove(String roomCode, Long userId) {
        if (roomCode == null || userId == null) {
            return null;
        }

        // Evict cached mute state
        removeMuteStatus(roomCode, userId);

        // Evict presenter state if this user was presenting
        Optional<PresenterInfo> presenter = clearPresenterIfPresent(roomCode, userId);
        if (presenter.isPresent()) {
            broadcastScreenShareStop(roomCode, userId, "LEFT");
        }

        ConcurrentHashMap<Long, WebSocketSession> userMap = roomSessions.get(roomCode);
        if (userMap == null) {
            return null;
        }

        WebSocketSession removed = userMap.remove(userId);
        if (userMap.isEmpty()) {
            roomSessions.remove(roomCode, userMap);
        }

        return removed;
    }

    /**
     * Retrieves an active session by roomCode and userId.
     */
    public Optional<WebSocketSession> get(String roomCode, Long userId) {
        if (roomCode == null || userId == null) {
            return Optional.empty();
        }
        ConcurrentHashMap<Long, WebSocketSession> userMap = roomSessions.get(roomCode);
        if (userMap == null) {
            return Optional.empty();
        }
        WebSocketSession session = userMap.get(userId);
        if (session != null && session.isOpen()) {
            return Optional.of(session);
        }
        return Optional.empty();
    }

    /**
     * Updates the cached mute state for an active session in a room.
     */
    public void setMuteStatus(String roomCode, Long userId, boolean audioMuted, boolean videoMuted) {
        if (roomCode == null || userId == null) {
            return;
        }
        ConcurrentHashMap<Long, MuteStatusPayload> muteMap =
                roomMuteStates.computeIfAbsent(roomCode, k -> new ConcurrentHashMap<>());
        muteMap.put(userId, new MuteStatusPayload(audioMuted, videoMuted));
    }

    /**
     * Returns the cached mute state for a user in a room, if present.
     */
    public Optional<MuteStatusPayload> getMuteStatus(String roomCode, Long userId) {
        if (roomCode == null || userId == null) {
            return Optional.empty();
        }
        ConcurrentHashMap<Long, MuteStatusPayload> muteMap = roomMuteStates.get(roomCode);
        if (muteMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(muteMap.get(userId));
    }

    /**
     * Removes the cached mute state for a user in a room.
     */
    public void removeMuteStatus(String roomCode, Long userId) {
        if (roomCode == null || userId == null) {
            return;
        }
        ConcurrentHashMap<Long, MuteStatusPayload> muteMap = roomMuteStates.get(roomCode);
        if (muteMap != null) {
            muteMap.remove(userId);
            if (muteMap.isEmpty()) {
                roomMuteStates.remove(roomCode, muteMap);
            }
        }
    }

    /**
     * Checks if a cached mute status exists for a given user in a room.
     */
    public boolean hasMuteStatus(String roomCode, Long userId) {
        return getMuteStatus(roomCode, userId).isPresent();
    }

    /**
     * Returns the list of active peer summaries in a room (excluding the specified user),
     * populated with display names and current mute statuses.
     */
    public List<PeerParticipantDto> getPeers(String roomCode, Long excludeUserId) {
        ConcurrentHashMap<Long, WebSocketSession> userMap = roomSessions.get(roomCode);
        if (userMap == null || userMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<PeerParticipantDto> peers = new ArrayList<>();
        for (Map.Entry<Long, WebSocketSession> entry : userMap.entrySet()) {
            Long peerId = entry.getKey();
            if (excludeUserId != null && excludeUserId.equals(peerId)) {
                continue;
            }

            WebSocketSession session = entry.getValue();
            if (session.isOpen()) {
                String name = (String) session.getAttributes().getOrDefault("name", "Participant");
                MuteStatusPayload mute = getMuteStatus(roomCode, peerId)
                        .orElse(new MuteStatusPayload(false, false));
                peers.add(new PeerParticipantDto(peerId, name, mute.isAudioMuted(), mute.isVideoMuted()));
            }
        }
        return peers;
    }

    /**
     * Sends a signaling envelope directly to a target user in the specified room.
     * Returns true if sent successfully, false if the recipient was not found or closed.
     */
    public boolean sendTo(String roomCode, Long toUserId, SignalMessage message) {
        Optional<WebSocketSession> targetSessionOpt = get(roomCode, toUserId);
        if (targetSessionOpt.isEmpty()) {
            return false;
        }

        WebSocketSession session = targetSessionOpt.get();
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (IOException e) {
            log.error("Failed to send message to user {} in room {}: {}", toUserId, roomCode, e.getMessage());
            return false;
        }
    }

    /**
     * Broadcasts a signaling envelope to all active peers in a room except the sender.
     */
    public int broadcast(String roomCode, Long excludeUserId, SignalMessage message) {
        ConcurrentHashMap<Long, WebSocketSession> userMap = roomSessions.get(roomCode);
        if (userMap == null || userMap.isEmpty()) {
            return 0;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (IOException e) {
            log.error("Failed to serialize broadcast message for room {}: {}", roomCode, e.getMessage());
            return 0;
        }

        TextMessage textMessage = new TextMessage(json);
        int count = 0;
        for (Map.Entry<Long, WebSocketSession> entry : userMap.entrySet()) {
            Long userId = entry.getKey();
            if (excludeUserId != null && excludeUserId.equals(userId)) {
                continue;
            }

            WebSocketSession session = entry.getValue();
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                    count++;
                } catch (IOException e) {
                    log.warn("Broadcast delivery failed for user {} in room {}: {}", userId, roomCode, e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * Closes all active sessions in a room with the specified CloseStatus, after transmitting an optional notice.
     */
    public void closeRoom(String roomCode, CloseStatus status, SignalMessage notice) {
        ConcurrentHashMap<Long, WebSocketSession> userMap = roomSessions.remove(roomCode);
        roomMuteStates.remove(roomCode);

        if (userMap == null || userMap.isEmpty()) {
            return;
        }

        TextMessage noticeMessage = null;
        if (notice != null) {
            try {
                noticeMessage = new TextMessage(objectMapper.writeValueAsString(notice));
            } catch (IOException e) {
                log.warn("Failed to serialize room close notice: {}", e.getMessage());
            }
        }

        for (Map.Entry<Long, WebSocketSession> entry : userMap.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (session.isOpen()) {
                try {
                    if (noticeMessage != null) {
                        session.sendMessage(noticeMessage);
                    }
                    session.close(status);
                } catch (IOException e) {
                    log.warn("Error closing session for user {} during room termination: {}", entry.getKey(), e.getMessage());
                }
            }
        }
        roomPresenters.remove(roomCode);
    }

    /**
     * Atomically locks the presenter slot for a room.
     * Returns true if successfully started; false if another participant is already presenting.
     */
    public boolean startScreenShare(String roomCode, Long userId, String streamId) {
        if (roomCode == null || userId == null || streamId == null || streamId.isBlank()) {
            return false;
        }
        AtomicBoolean success = new AtomicBoolean(false);
        roomPresenters.compute(roomCode, (k, existing) -> {
            if (existing == null) {
                success.set(true);
                return new PresenterInfo(userId, streamId.trim());
            }
            if (existing.userId().equals(userId)) {
                success.set(true);
                return new PresenterInfo(userId, streamId.trim());
            }
            success.set(false);
            return existing;
        });
        return success.get();
    }

    /**
     * Stops the active screen share if the requester is either the active presenter or the room host.
     * Returns the cleared PresenterInfo if removed, or empty if unauthorized / not presenting.
     */
    public Optional<PresenterInfo> stopScreenShare(String roomCode, Long requesterId, boolean isHost) {
        if (roomCode == null || requesterId == null) {
            return Optional.empty();
        }
        AtomicReference<PresenterInfo> removed = new AtomicReference<>(null);
        roomPresenters.computeIfPresent(roomCode, (k, existing) -> {
            if (existing.userId().equals(requesterId) || isHost) {
                removed.set(existing);
                return null;
            }
            return existing;
        });
        return Optional.ofNullable(removed.get());
    }

    /**
     * Clears presenter state if the given user is currently registered as the presenter.
     */
    public Optional<PresenterInfo> clearPresenterIfPresent(String roomCode, Long userId) {
        if (roomCode == null || userId == null) {
            return Optional.empty();
        }
        AtomicReference<PresenterInfo> removed = new AtomicReference<>(null);
        roomPresenters.computeIfPresent(roomCode, (k, existing) -> {
            if (existing.userId().equals(userId)) {
                removed.set(existing);
                return null;
            }
            return existing;
        });
        return Optional.ofNullable(removed.get());
    }

    /**
     * Retrieves the current active screen presenter for a room.
     */
    public Optional<PresenterInfo> getPresenter(String roomCode) {
        if (roomCode == null) return Optional.empty();
        return Optional.ofNullable(roomPresenters.get(roomCode));
    }

    /**
     * Broadcasts a screen-share-stop envelope to peers in the room.
     */
    public void broadcastScreenShareStop(String roomCode, Long presenterUserId, String reason) {
        SignalMessage stopMessage = new SignalMessage(
                MessageType.SCREEN_SHARE_STOP,
                roomCode,
                presenterUserId,
                null,
                new ScreenShareStopPayload(presenterUserId, reason)
        );
        broadcast(roomCode, presenterUserId, stopMessage);
    }

    /**
     * Total number of active rooms currently registered.
     */
    public int getActiveRoomCount() {
        return roomSessions.size();
    }

    /**
     * Total number of active participants across all rooms.
     */
    public int getTotalSessionCount() {
        return roomSessions.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Active participant count for a specific room.
     */
    public int getParticipantCount(String roomCode) {
        ConcurrentHashMap<Long, WebSocketSession> userMap = roomSessions.get(roomCode);
        return userMap != null ? userMap.size() : 0;
    }
}


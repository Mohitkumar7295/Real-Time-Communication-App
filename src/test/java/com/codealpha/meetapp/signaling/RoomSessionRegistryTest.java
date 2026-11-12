package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.signaling.dto.PeerParticipantDto;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomSessionRegistryTest {

    private RoomSessionRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new RoomSessionRegistry(objectMapper);
    }

    private WebSocketSession mockSession(String id, Long userId, String roomCode, String name) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", userId);
        attributes.put("roomCode", roomCode);
        attributes.put("name", name);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    @DisplayName("add and get session successfully retrieves registered session")
    void addAndGetSession() {
        WebSocketSession session = mockSession("s1", 1L, "DEMO1001", "Aarav");
        registry.add("DEMO1001", 1L, session);

        assertTrue(registry.get("DEMO1001", 1L).isPresent());
        assertEquals(1, registry.getParticipantCount("DEMO1001"));
    }

    @Test
    @DisplayName("replacement closes old session with code 4000 and clears cached mute state")
    void replacement_closesOldSessionWithCode4000_andClearsMuteState() throws IOException {
        WebSocketSession oldSession = mockSession("s1", 1L, "DEMO1001", "Aarav");
        registry.add("DEMO1001", 1L, oldSession);

        // Cache mute state for user 1
        registry.setMuteStatus("DEMO1001", 1L, true, true);
        assertTrue(registry.hasMuteStatus("DEMO1001", 1L));

        // Register new session for same user (tab replacement)
        WebSocketSession newSession = mockSession("s2", 1L, "DEMO1001", "Aarav");
        registry.add("DEMO1001", 1L, newSession);

        // Verify old session closed with code 4000
        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(oldSession).close(statusCaptor.capture());
        assertEquals(4000, statusCaptor.getValue().getCode());
        assertEquals("Replaced by newer session", statusCaptor.getValue().getReason());

        // Verify cached mute state was evicted on replacement
        assertFalse(registry.hasMuteStatus("DEMO1001", 1L), "Cached mute state should be evicted on session replacement");
    }

    @Test
    @DisplayName("remove session evicts session and removes cached mute state")
    void removeSession_clearsSessionAndMuteState() {
        WebSocketSession session = mockSession("s1", 1L, "DEMO1001", "Aarav");
        registry.add("DEMO1001", 1L, session);
        registry.setMuteStatus("DEMO1001", 1L, true, false);
        assertTrue(registry.hasMuteStatus("DEMO1001", 1L));

        registry.remove("DEMO1001", 1L);

        assertFalse(registry.get("DEMO1001", 1L).isPresent());
        assertFalse(registry.hasMuteStatus("DEMO1001", 1L), "Mute state must be evicted when session is removed");
        assertEquals(0, registry.getParticipantCount("DEMO1001"));
    }

    @Test
    @DisplayName("getPeers includes latest mute status for other room participants")
    void getPeers_includesMuteStatus() {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        registry.add("DEMO1001", 1L, s1);
        registry.add("DEMO1001", 2L, s2);

        // User 2 mutes audio
        registry.setMuteStatus("DEMO1001", 2L, true, false);

        List<PeerParticipantDto> peersForUser1 = registry.getPeers("DEMO1001", 1L);
        assertEquals(1, peersForUser1.size());
        PeerParticipantDto peer = peersForUser1.get(0);
        assertEquals(2L, peer.getUserId());
        assertEquals("Priya", peer.getName());
        assertTrue(peer.isAudioMuted());
        assertFalse(peer.isVideoMuted());
    }

    @Test
    @DisplayName("closeRoom notifies all peers with notice and closes sessions with code 4010")
    void closeRoom_notifiesAndClosesAllSessions() throws IOException {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        registry.add("DEMO1001", 1L, s1);
        registry.add("DEMO1001", 2L, s2);

        SignalMessage notice = new SignalMessage(MessageType.ROOM_ENDED, "DEMO1001", 1L, null, Map.of("message", "Host ended"));
        registry.closeRoom("DEMO1001", new CloseStatus(4010, "Room ended by host"), notice);

        verify(s1).sendMessage(any(TextMessage.class));
        verify(s1).close(argThat(status -> status.getCode() == 4010));
        verify(s2).sendMessage(any(TextMessage.class));
        verify(s2).close(argThat(status -> status.getCode() == 4010));

        assertEquals(0, registry.getParticipantCount("DEMO1001"));
    }

    @Test
    @DisplayName("thread safety under concurrent add and remove operations")
    void concurrentAddRemove_threadSafety() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long userId = i + 1;
            executor.submit(() -> {
                try {
                    for (int op = 0; op < operationsPerThread; op++) {
                        WebSocketSession s = mockSession("s-" + userId + "-" + op, userId, "DEMO1001", "User" + userId);
                        registry.add("DEMO1001", userId, s);
                        registry.setMuteStatus("DEMO1001", userId, op % 2 == 0, false);
                        registry.getPeers("DEMO1001", userId);
                        if (op % 3 == 0) {
                            registry.remove("DEMO1001", userId);
                        }
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(0, errors.get(), "Concurrent access caused exceptions");
    }
}


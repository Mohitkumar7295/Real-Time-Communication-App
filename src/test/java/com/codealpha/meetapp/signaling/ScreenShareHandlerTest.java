package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.signaling.dto.PresenterInfo;
import com.codealpha.meetapp.signaling.handler.ScreenShareHandler;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ScreenShareHandlerTest {

    private RoomSessionRegistry registry;
    private RoomRepository roomRepository;
    private RateLimiter rateLimiter;
    private ObjectMapper objectMapper;
    private ScreenShareHandler screenShareHandler;

    private User hostUser;
    private User guestUser1;
    private User guestUser2;
    private Room demoRoom;

    private WebSocketSession hostSession;
    private WebSocketSession guestSession1;
    private WebSocketSession guestSession2;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new RoomSessionRegistry(objectMapper);
        roomRepository = mock(RoomRepository.class);
        rateLimiter = new RateLimiter();
        screenShareHandler = new ScreenShareHandler(registry, roomRepository, rateLimiter, objectMapper);

        hostUser = new User("Host", "host@example.com", "hash");
        org.springframework.test.util.ReflectionTestUtils.setField(hostUser, "id", 1L);
        guestUser1 = new User("Aarav", "aarav@example.com", "hash");
        org.springframework.test.util.ReflectionTestUtils.setField(guestUser1, "id", 2L);
        guestUser2 = new User("Priya", "priya@example.com", "hash");
        org.springframework.test.util.ReflectionTestUtils.setField(guestUser2, "id", 3L);
        demoRoom = new Room("DEMO1001", hostUser);

        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(demoRoom));

        hostSession = mockSession("s-host", 1L, "DEMO1001", "Host");
        guestSession1 = mockSession("s-guest1", 2L, "DEMO1001", "Aarav");
        guestSession2 = mockSession("s-guest2", 3L, "DEMO1001", "Priya");

        registry.add("DEMO1001", 1L, hostSession);
        registry.add("DEMO1001", 2L, guestSession1);
        registry.add("DEMO1001", 3L, guestSession2);
    }

    private WebSocketSession mockSession(String id, Long userId, String roomCode, String name) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put("roomCode", roomCode);
        attrs.put("name", name);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getUri()).thenReturn(URI.create("http://localhost:8080/ws?room=" + roomCode));
        return session;
    }

    @Test
    @DisplayName("start accepted: records presenter in registry and broadcasts to peers")
    void start_acceptedAndBroadcast() throws Exception {
        SignalMessage startMsg = new SignalMessage(
                MessageType.SCREEN_SHARE_START,
                "DEMO1001",
                2L,
                null,
                Map.of("streamId", "scr-stream-1", "label", "Window")
        );

        screenShareHandler.handle(guestSession1, startMsg);

        // Registry has presenter
        Optional<PresenterInfo> presenterOpt = registry.getPresenter("DEMO1001");
        assertTrue(presenterOpt.isPresent());
        assertEquals(2L, presenterOpt.get().userId());
        assertEquals("scr-stream-1", presenterOpt.get().streamId());

        // Peers received broadcast
        verify(hostSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("screen-share-start") &&
                        tm.getPayload().contains("scr-stream-1")
        ));
        verify(guestSession2).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("screen-share-start") &&
                        tm.getPayload().contains("scr-stream-1")
        ));
        // Sender does not receive their own broadcast
        verify(guestSession1, never()).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm && tm.getPayload().contains("screen-share-start")
        ));
    }

    @Test
    @DisplayName("second start rejected: caller receives error with code ALREADY_PRESENTING")
    void secondStart_rejected_alreadyPresenting() throws Exception {
        // User 2 starts sharing
        SignalMessage startMsg1 = new SignalMessage(
                MessageType.SCREEN_SHARE_START,
                "DEMO1001",
                2L,
                null,
                Map.of("streamId", "scr-stream-1")
        );
        screenShareHandler.handle(guestSession1, startMsg1);

        // User 3 attempts to start sharing
        SignalMessage startMsg2 = new SignalMessage(
                MessageType.SCREEN_SHARE_START,
                "DEMO1001",
                3L,
                null,
                Map.of("streamId", "scr-stream-2")
        );
        screenShareHandler.handle(guestSession2, startMsg2);

        // User 3 receives ALREADY_PRESENTING error
        verify(guestSession2).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("ALREADY_PRESENTING") &&
                        tm.getPayload().contains("Another participant is already sharing")
        ));

        // Presenter remains User 2
        assertEquals(2L, registry.getPresenter("DEMO1001").get().userId());
    }

    @Test
    @DisplayName("concurrent starts: atomic enforcement ensures exactly one presenter succeeds")
    void concurrentStarts_atomicEnforcement() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final long uid = 100L + i;
            executor.submit(() -> {
                try {
                    latch.await();
                    boolean acquired = registry.startScreenShare("DEMO1001", uid, "stream-" + uid);
                    if (acquired) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));

        assertEquals(1, successCount.get());
        assertTrue(registry.getPresenter("DEMO1001").isPresent());
    }

    @Test
    @DisplayName("stop clears state: presenter self-stop clears registry and broadcasts to peers")
    void stop_clearsStateAndBroadcasts() throws Exception {
        // Start share
        registry.startScreenShare("DEMO1001", 2L, "scr-1");

        // Stop share
        SignalMessage stopMsg = new SignalMessage(
                MessageType.SCREEN_SHARE_STOP,
                "DEMO1001",
                2L,
                null,
                Map.of("reason", "USER_STOPPED")
        );
        screenShareHandler.handle(guestSession1, stopMsg);

        // Presenter cleared
        assertTrue(registry.getPresenter("DEMO1001").isEmpty());

        // Broadcast sent with reason USER_STOPPED
        verify(hostSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("screen-share-stop") &&
                        tm.getPayload().contains("USER_STOPPED")
        ));
    }

    @Test
    @DisplayName("host can stop another presenter: presenter receives HOST_STOPPED and peers notified")
    void hostCanStopAnotherPresenter() throws Exception {
        // User 2 starts share
        registry.startScreenShare("DEMO1001", 2L, "scr-1");

        // Host (user 1) stops user 2's share
        SignalMessage hostStopMsg = new SignalMessage(
                MessageType.SCREEN_SHARE_STOP,
                "DEMO1001",
                1L,
                null,
                Map.of("reason", "HOST_STOPPED")
        );
        screenShareHandler.handle(hostSession, hostStopMsg);

        // Presenter cleared
        assertTrue(registry.getPresenter("DEMO1001").isEmpty());

        // Presenter (guest 1) receives HOST_STOPPED
        verify(guestSession1).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("screen-share-stop") &&
                        tm.getPayload().contains("HOST_STOPPED")
        ));
    }

    @Test
    @DisplayName("guest cannot stop another's share: rejected with FORBIDDEN")
    void guestCannotStopAnotherShare() throws Exception {
        // User 2 starts share
        registry.startScreenShare("DEMO1001", 2L, "scr-1");

        // User 3 (guest) attempts to stop user 2's share
        SignalMessage guestStopMsg = new SignalMessage(
                MessageType.SCREEN_SHARE_STOP,
                "DEMO1001",
                3L,
                null,
                Map.of("reason", "USER_STOPPED")
        );
        screenShareHandler.handle(guestSession2, guestStopMsg);

        // User 3 gets FORBIDDEN error
        verify(guestSession2).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("FORBIDDEN") &&
                        tm.getPayload().contains("Only the presenter or the meeting host")
        ));

        // Presenter remains active
        assertTrue(registry.getPresenter("DEMO1001").isPresent());
    }

    @Test
    @DisplayName("presenter remove clears presenter state and broadcasts stop")
    void presenterRemove_clearsState() throws Exception {
        registry.startScreenShare("DEMO1001", 2L, "scr-1");
        assertTrue(registry.getPresenter("DEMO1001").isPresent());

        // Remove user 2
        registry.remove("DEMO1001", 2L);

        // Presenter cleared
        assertTrue(registry.getPresenter("DEMO1001").isEmpty());

        // Remaining peers receive screen-share-stop with reason LEFT
        verify(hostSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("screen-share-stop") &&
                        tm.getPayload().contains("LEFT")
        ));
    }

    @Test
    @DisplayName("replaced session clears presenter state")
    void replacedSession_clearsPresenterState() throws Exception {
        registry.startScreenShare("DEMO1001", 2L, "scr-1");

        // User 2 opens a second tab (replacement)
        WebSocketSession replacementSession = mockSession("s-guest2-tab2", 2L, "DEMO1001", "Aarav");
        registry.add("DEMO1001", 2L, replacementSession);

        // Presenter state cleared
        assertTrue(registry.getPresenter("DEMO1001").isEmpty());

        // Peers receive screen-share-stop with reason DISCONNECTED
        verify(hostSession).sendMessage(argThat(msg ->
                msg instanceof TextMessage tm &&
                        tm.getPayload().contains("screen-share-stop") &&
                        tm.getPayload().contains("DISCONNECTED")
        ));
    }

    @Test
    @DisplayName("rate limit exceeded: sending > 10 control events in a minute triggers error")
    void rateLimit_exceeded() throws Exception {
        SignalMessage msg = new SignalMessage(
                MessageType.SCREEN_SHARE_STOP,
                "DEMO1001",
                2L,
                null,
                Map.of("reason", "USER_STOPPED")
        );

        for (int i = 0; i < 10; i++) {
            screenShareHandler.handle(guestSession1, msg);
        }

        // 11th call triggers rate limit
        screenShareHandler.handle(guestSession1, msg);

        verify(guestSession1).sendMessage(argThat(m ->
                m instanceof TextMessage tm &&
                        tm.getPayload().contains("RATE_LIMIT_EXCEEDED")
        ));
    }

    @Test
    @DisplayName("client sending server-only stop reasons (LEFT, DISCONNECTED, ROOM_ENDED) is rejected with INVALID_PAYLOAD")
    void clientSendingServerOnlyReasons_rejectedWithInvalidPayload() throws Exception {
        registry.startScreenShare("DEMO1001", 2L, "scr-1");

        for (String forbiddenReason : List.of("LEFT", "DISCONNECTED", "ROOM_ENDED")) {
            SignalMessage msg = new SignalMessage(
                    MessageType.SCREEN_SHARE_STOP,
                    "DEMO1001",
                    2L,
                    null,
                    Map.of("reason", forbiddenReason)
            );
            screenShareHandler.handle(guestSession1, msg);

            verify(guestSession1, atLeastOnce()).sendMessage(argThat(m ->
                    m instanceof TextMessage tm &&
                            tm.getPayload().contains("INVALID_PAYLOAD") &&
                            tm.getPayload().contains("Invalid screen-share-stop reason")
            ));
        }

        // Presenter remains active
        assertTrue(registry.getPresenter("DEMO1001").isPresent());
    }

    @Test
    @DisplayName("guest sending HOST_STOPPED is rejected with FORBIDDEN")
    void guestSendingHostStopped_rejectedWithForbidden() throws Exception {
        registry.startScreenShare("DEMO1001", 2L, "scr-1");

        SignalMessage msg = new SignalMessage(
                MessageType.SCREEN_SHARE_STOP,
                "DEMO1001",
                3L,
                null,
                Map.of("reason", "HOST_STOPPED")
        );
        screenShareHandler.handle(guestSession2, msg);

        verify(guestSession2).sendMessage(argThat(m ->
                m instanceof TextMessage tm &&
                        tm.getPayload().contains("FORBIDDEN") &&
                        tm.getPayload().contains("Only the meeting host can specify HOST_STOPPED")
        ));
    }
}

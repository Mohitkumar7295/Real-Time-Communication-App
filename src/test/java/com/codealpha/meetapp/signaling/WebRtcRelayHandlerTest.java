package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.signaling.dto.MuteStatusPayload;
import com.codealpha.meetapp.signaling.handler.MuteStatusHandler;
import com.codealpha.meetapp.signaling.handler.WebRtcRelayHandler;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebRtcRelayHandlerTest {

    private RoomSessionRegistry registry;
    private WebRtcRelayHandler relayHandler;
    private MuteStatusHandler muteStatusHandler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new RoomSessionRegistry(objectMapper);
        relayHandler = new WebRtcRelayHandler(registry, objectMapper);
        muteStatusHandler = new MuteStatusHandler(registry, objectMapper);
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
    @DisplayName("offer is relayed only to target peer in the same room")
    void offer_relayedOnlyToTargetInSameRoom() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        registry.add("DEMO1001", 1L, s1);
        registry.add("DEMO1001", 2L, s2);

        SignalMessage offer = new SignalMessage(
                MessageType.OFFER,
                "DEMO1001",
                1L,
                2L,
                Map.of("sdp", "v=0\r\no=alice 12345 12345 IN IP4 127.0.0.1", "type", "offer")
        );

        relayHandler.handle(s1, offer);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(s2).sendMessage(captor.capture());
        String relayedPayload = captor.getValue().getPayload();
        assertTrue(relayedPayload.contains("\"type\":\"offer\""));
        assertTrue(relayedPayload.contains("\"from\":1"));
        assertTrue(relayedPayload.contains("\"to\":2"));
    }

    @Test
    @DisplayName("message to peer in another room is rejected with error to sender")
    void messageToPeerInAnotherRoom_rejected() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s3 = mockSession("s3", 3L, "OTHER999", "Vikram");
        registry.add("DEMO1001", 1L, s1);
        registry.add("OTHER999", 3L, s3);

        SignalMessage offer = new SignalMessage(
                MessageType.OFFER,
                "DEMO1001",
                1L,
                3L,
                Map.of("sdp", "v=0\r\no=alice...", "type", "offer")
        );

        relayHandler.handle(s1, offer);

        // Target s3 in another room never receives message
        verify(s3, never()).sendMessage(any(TextMessage.class));

        // Sender s1 receives error envelope
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(s1).sendMessage(captor.capture());
        String errorPayload = captor.getValue().getPayload();
        assertTrue(errorPayload.contains("\"type\":\"error\""));
        assertTrue(errorPayload.contains("TARGET_NOT_FOUND"));
    }

    @Test
    @DisplayName("spoofed client 'from' is strictly ignored and overwritten with verified session userId")
    void spoofedFrom_isIgnoredAndOverwritten() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        registry.add("DEMO1001", 1L, s1);
        registry.add("DEMO1001", 2L, s2);

        // Sender 1 attempts to impersonate User 999
        SignalMessage spoofedMessage = new SignalMessage(
                MessageType.OFFER,
                "DEMO1001",
                999L,
                2L,
                Map.of("sdp", "v=0...", "type", "offer")
        );

        relayHandler.handle(s1, spoofedMessage);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(s2).sendMessage(captor.capture());
        String relayed = captor.getValue().getPayload();
        assertTrue(relayed.contains("\"from\":1"), "Server must overwrite spoofed 'from' with authenticated user ID 1");
        assertFalse(relayed.contains("\"from\":999"), "Spoofed ID 999 must not appear in forwarded payload");
    }

    @Test
    @DisplayName("oversized SDP closes connection with code 1009 and sends error")
    void oversizedSdp_closesSocketWithCode1009() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        registry.add("DEMO1001", 1L, s1);

        // Generate SDP payload > 16 KB
        String largeSdp = "a".repeat(17 * 1024);
        SignalMessage oversized = new SignalMessage(
                MessageType.OFFER,
                "DEMO1001",
                1L,
                2L,
                Map.of("sdp", largeSdp, "type", "offer")
        );

        relayHandler.handle(s1, oversized);

        verify(s1).sendMessage(argThat(msg -> msg instanceof TextMessage tm && tm.getPayload().contains("PAYLOAD_TOO_LARGE")));
        verify(s1).close(argThat(status -> status.getCode() == 1009));
    }

    @Test
    @DisplayName("oversized ICE candidate sends error per contract")
    void oversizedCandidate_errorsPerContract() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        registry.add("DEMO1001", 1L, s1);

        // Candidate > 2 KB
        String largeCandidate = "c".repeat(3 * 1024);
        SignalMessage oversized = new SignalMessage(
                MessageType.ICE_CANDIDATE,
                "DEMO1001",
                1L,
                2L,
                Map.of("candidate", largeCandidate)
        );

        relayHandler.handle(s1, oversized);

        verify(s1).sendMessage(argThat(msg -> msg instanceof TextMessage tm && tm.getPayload().contains("PAYLOAD_TOO_LARGE")));
        verify(s1, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("media relay rate limit triggers error when burst exceeded")
    void mediaRateLimit_burstExceeded_returnsError() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        registry.add("DEMO1001", 1L, s1);
        registry.add("DEMO1001", 2L, s2);

        SignalMessage candidate = new SignalMessage(
                MessageType.ICE_CANDIDATE,
                "DEMO1001",
                1L,
                2L,
                Map.of("candidate", "candidate:1 1 UDP ...")
        );

        // Burst is 100: send 105 in tight loop
        for (int i = 0; i < 105; i++) {
            relayHandler.handle(s1, candidate);
        }

        // At least one error message for RATE_LIMIT_EXCEEDED should be delivered to s1
        verify(s1, atLeastOnce()).sendMessage(argThat(msg -> msg instanceof TextMessage tm && tm.getPayload().contains("RATE_LIMIT_EXCEEDED")));
    }

    @Test
    @DisplayName("mute-status broadcasts to peers and updates registry cache")
    void muteStatus_broadcastsToPeersAndUpdatesRegistry() throws Exception {
        WebSocketSession s1 = mockSession("s1", 1L, "DEMO1001", "Aarav");
        WebSocketSession s2 = mockSession("s2", 2L, "DEMO1001", "Priya");
        registry.add("DEMO1001", 1L, s1);
        registry.add("DEMO1001", 2L, s2);

        MuteStatusPayload payload = new MuteStatusPayload(true, false);
        SignalMessage muteMessage = new SignalMessage(
                MessageType.MUTE_STATUS,
                "DEMO1001",
                1L,
                null,
                payload
        );

        muteStatusHandler.handle(s1, muteMessage);

        // 1. Registry cached state
        assertTrue(registry.hasMuteStatus("DEMO1001", 1L));
        assertEquals(true, registry.getMuteStatus("DEMO1001", 1L).get().isAudioMuted());
        assertEquals(false, registry.getMuteStatus("DEMO1001", 1L).get().isVideoMuted());

        // 2. Broadcast to Peer 2 (Sender 1 excluded)
        verify(s2).sendMessage(argThat(msg -> msg instanceof TextMessage tm && tm.getPayload().contains("\"audioMuted\":true")));
        verify(s1, never()).sendMessage(any(TextMessage.class));
    }
}

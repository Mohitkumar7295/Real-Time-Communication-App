package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomParticipant;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.security.JwtService;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtHandshakeInterceptorTest {

    private JwtService jwtService;
    private WsTicketService wsTicketService;
    private UserRepository userRepository;
    private RoomRepository roomRepository;
    private RoomParticipantRepository roomParticipantRepository;
    private AppProperties appProperties;
    private JwtHandshakeInterceptor interceptor;

    private ServerHttpRequest request;
    private ServerHttpResponse response;
    private WebSocketHandler wsHandler;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        wsTicketService = mock(WsTicketService.class);
        userRepository = mock(UserRepository.class);
        roomRepository = mock(RoomRepository.class);
        roomParticipantRepository = mock(RoomParticipantRepository.class);
        appProperties = new AppProperties();
        appProperties.setAllowedOrigin("http://localhost:8080");

        interceptor = new JwtHandshakeInterceptor(
                jwtService,
                wsTicketService,
                userRepository,
                roomRepository,
                roomParticipantRepository,
                appProperties
        );

        request = mock(ServerHttpRequest.class);
        response = mock(ServerHttpResponse.class);
        wsHandler = mock(WebSocketHandler.class);
    }

    private void setupHeaders(String origin, String uriString) {
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) {
            headers.set("Origin", origin);
        }
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create(uriString));
    }

    @Test
    @DisplayName("handshake rejected with 403 Forbidden when Origin does not match allowed origin")
    void handshake_rejectedFromWrongOrigin() {
        setupHeaders("http://unauthorized-domain.com", "http://localhost:8080/ws?token=abc&room=DEMO1001");

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("handshake rejected with 401 Unauthorized when token is missing")
    void handshake_rejectedWithoutToken() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?room=DEMO1001");

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("handshake rejected with 401 Unauthorized when JWT is invalid")
    void handshake_rejectedWithInvalidToken() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?token=invalid_jwt&room=DEMO1001");
        when(jwtService.extractUserId("invalid_jwt")).thenReturn(Optional.empty());

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("handshake rejected with 404 Not Found when room does not exist")
    void handshake_rejectedWhenRoomNotFound() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?token=valid_jwt&room=DEMO1001");
        when(jwtService.extractUserId("valid_jwt")).thenReturn(Optional.of(1L));

        User user = new User("Aarav", "aarav@example.com", "hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.empty());

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("handshake rejected with 403 Forbidden when user is not an active participant")
    void handshake_rejectedWhenNotActiveParticipant() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?token=valid_jwt&room=DEMO1001");
        when(jwtService.extractUserId("valid_jwt")).thenReturn(Optional.of(1L));

        User host = new User("Host", "host@example.com", "hash");
        User user = new User("Aarav", "aarav@example.com", "hash");
        Room room = new Room("DEMO1001", host);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), 1L))
                .thenReturn(Optional.empty());

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("handshake accepted and populates session attributes when all checks pass")
    void handshake_acceptedWhenValid() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?token=valid_jwt&room=DEMO1001");
        when(jwtService.extractUserId("valid_jwt")).thenReturn(Optional.of(1L));

        User host = new User("Host", "host@example.com", "hash");
        User user = new User("Aarav", "aarav@example.com", "hash");
        Room room = new Room("DEMO1001", host);
        RoomParticipant participant = new RoomParticipant(room, user);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), 1L))
                .thenReturn(Optional.of(participant));
        when(roomParticipantRepository.countByRoomIdAndLeftAtIsNull(room.getId())).thenReturn(1L);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(result);
        assertEquals(1L, attributes.get("userId"));
        assertEquals("DEMO1001", attributes.get("roomCode"));
        assertEquals("Aarav", attributes.get("name"));
    }

    @Test
    @DisplayName("handshake accepted and populates session attributes when valid ticket supplied")
    void handshake_acceptedWhenValidTicket() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?ticket=abc123ticket&room=DEMO1001");
        when(wsTicketService.consumeTicket("abc123ticket")).thenReturn(Optional.of(2L));

        User host = new User("Host", "host@example.com", "hash");
        User user = new User("Diya", "diya@example.com", "hash");
        Room room = new Room("DEMO1001", host);
        RoomParticipant participant = new RoomParticipant(room, user);

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), 2L))
                .thenReturn(Optional.of(participant));
        when(roomParticipantRepository.countByRoomIdAndLeftAtIsNull(room.getId())).thenReturn(1L);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(result);
        assertEquals(2L, attributes.get("userId"));
        assertEquals("DEMO1001", attributes.get("roomCode"));
        assertEquals("Diya", attributes.get("name"));
    }

    @Test
    @DisplayName("handshake rejected when ticket is invalid or already consumed")
    void handshake_rejectedWhenTicketInvalid() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?ticket=consumed_ticket&room=DEMO1001");
        when(wsTicketService.consumeTicket("consumed_ticket")).thenReturn(Optional.empty());

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}


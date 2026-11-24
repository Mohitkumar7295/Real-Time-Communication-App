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
        setupHeaders("http://unauthorized-domain.com", "http://localhost:8080/ws?ticket=abc&code=K7M2QX9P");

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("handshake rejected with 401 Unauthorized when raw JWT token is passed in query string")
    void handshake_rejectedWhenTokenInQueryString() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?token=raw_jwt_value&code=K7M2QX9P");

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("handshake rejected with 401 Unauthorized when ticket is missing")
    void handshake_rejectedWithoutTicket() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?code=K7M2QX9P");

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("handshake rejected with 401 Unauthorized when ticket is invalid or consumed")
    void handshake_rejectedWithInvalidTicket() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?ticket=invalid_or_used&code=K7M2QX9P");
        when(wsTicketService.consumeTicket("invalid_or_used")).thenReturn(Optional.empty());

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("handshake rejected with 404 Not Found when room does not exist")
    void handshake_rejectedWhenRoomNotFound() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?ticket=valid_ticket&code=K7M2QX9P");
        when(wsTicketService.consumeTicket("valid_ticket")).thenReturn(Optional.of(1L));

        User user = new User("Aarav", "aarav@example.com", "hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roomRepository.findByRoomCode("K7M2QX9P")).thenReturn(Optional.empty());

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("handshake rejected with 403 Forbidden when user is not an active participant")
    void handshake_rejectedWhenNotActiveParticipant() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?ticket=valid_ticket&code=K7M2QX9P");
        when(wsTicketService.consumeTicket("valid_ticket")).thenReturn(Optional.of(1L));

        User host = new User("Host", "host@example.com", "hash");
        User user = new User("Aarav", "aarav@example.com", "hash");
        Room room = new Room("K7M2QX9P", host);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roomRepository.findByRoomCode("K7M2QX9P")).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), 1L))
                .thenReturn(Optional.empty());

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("handshake accepted and populates session attributes when ticket and room checks pass")
    void handshake_acceptedWhenValid() {
        setupHeaders("http://localhost:8080", "http://localhost:8080/ws?ticket=valid_ticket&code=K7M2QX9P");
        when(wsTicketService.consumeTicket("valid_ticket")).thenReturn(Optional.of(1L));

        User host = new User("Host", "host@example.com", "hash");
        User user = new User("Aarav", "aarav@example.com", "hash");
        Room room = new Room("K7M2QX9P", host);
        RoomParticipant participant = new RoomParticipant(room, user);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roomRepository.findByRoomCode("K7M2QX9P")).thenReturn(Optional.of(room));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), 1L))
                .thenReturn(Optional.of(participant));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(result);
        assertEquals(1L, attributes.get("userId"));
        assertEquals("K7M2QX9P", attributes.get("roomCode"));
        assertEquals("Aarav", attributes.get("name"));
        assertEquals(room.getId(), attributes.get("roomId"));
    }
}

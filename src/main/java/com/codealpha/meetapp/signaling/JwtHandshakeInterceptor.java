package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.security.JwtService;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

/**
 * Handshake interceptor authenticating WebSocket upgrade requests:
 * 1. Strictly validates the Origin header against app.allowed-origin.
 * 2. Validates Bearer token or ticket in query string.
 * 3. Enforces that the user is an active participant in room_participants for that room.
 * 4. Populates session attributes: userId, roomCode, roomId, name.
 * Never logs raw credentials, tokens, or query strings.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    private final JwtService jwtService;
    private final WsTicketService wsTicketService;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final AppProperties appProperties;

    public JwtHandshakeInterceptor(JwtService jwtService,
                                   WsTicketService wsTicketService,
                                   UserRepository userRepository,
                                   RoomRepository roomRepository,
                                   RoomParticipantRepository roomParticipantRepository,
                                   AppProperties appProperties) {
        this.jwtService = jwtService;
        this.wsTicketService = wsTicketService;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.appProperties = appProperties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        // 1. Origin Header Check
        String origin = request.getHeaders().getOrigin();
        String allowedOrigin = appProperties.getAllowedOrigin();
        if (origin == null || !origin.equalsIgnoreCase(allowedOrigin)) {
            log.warn("WebSocket handshake rejected: Origin mismatch. Received: '{}', Allowed: '{}'",
                    origin, allowedOrigin);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        // 2. Query String Extraction
        var queryParams = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String ticket = queryParams.getFirst("ticket");
        String token = queryParams.getFirst("token");
        String roomCode = queryParams.getFirst("room");
        if (roomCode == null) {
            roomCode = queryParams.getFirst("roomCode");
        }

        if ((ticket == null || ticket.isBlank()) && (token == null || token.isBlank())) {
            log.warn("WebSocket handshake rejected: Missing ticket or token parameter");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        if (roomCode == null || roomCode.isBlank()) {
            log.warn("WebSocket handshake rejected: Missing room parameter");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 3. Ticket / JWT Authentication & User Lookup
        Optional<Long> userIdOpt;
        if (ticket != null && !ticket.isBlank()) {
            userIdOpt = wsTicketService.consumeTicket(ticket.trim());
            if (userIdOpt.isEmpty()) {
                log.warn("WebSocket handshake rejected: Invalid, expired, or already-consumed ticket");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        } else {
            userIdOpt = jwtService.extractUserId(token.trim());
            if (userIdOpt.isEmpty()) {
                log.warn("WebSocket handshake rejected: Invalid or expired JWT");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        }

        Long userId = userIdOpt.get();
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.warn("WebSocket handshake rejected: User ID {} not found", userId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        User user = userOpt.get();

        // 4. Room Existence & Active State Check
        String normalizedCode = roomCode.trim().toUpperCase();
        Optional<Room> roomOpt = roomRepository.findByRoomCode(normalizedCode);
        if (roomOpt.isEmpty() || !roomOpt.get().isActive()) {
            log.warn("WebSocket handshake rejected: Room {} not found or inactive", normalizedCode);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        Room room = roomOpt.get();

        // 5. Active Membership Check (must be active participant)
        boolean isParticipant = roomParticipantRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), userId)
                .isPresent();

        if (!isParticipant) {
            log.warn("WebSocket handshake rejected: User {} is not an active participant of room {}",
                    userId, normalizedCode);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        // 6. Capacity Check
        long activeCount = roomParticipantRepository.countByRoomIdAndLeftAtIsNull(room.getId());
        if (activeCount > appProperties.getMaxRoomParticipants()) {
            log.warn("WebSocket handshake rejected: Room {} exceeds maximum capacity ({}/{})",
                    normalizedCode, activeCount, appProperties.getMaxRoomParticipants());
            response.setStatusCode(HttpStatus.CONFLICT);
            return false;
        }

        // 7. Populate Session Attributes
        attributes.put("userId", userId);
        attributes.put("roomCode", room.getRoomCode());
        attributes.put("roomId", room.getId());
        attributes.put("name", user.getName());

        log.info("WebSocket handshake accepted for user {} in room {}", userId, room.getRoomCode());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        if (exception != null) {
            log.error("WebSocket handshake completed with error: {}", exception.getMessage());
        }
    }
}


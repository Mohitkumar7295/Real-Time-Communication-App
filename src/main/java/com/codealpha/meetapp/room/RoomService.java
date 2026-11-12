package com.codealpha.meetapp.room;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.BadRequestException;
import com.codealpha.meetapp.common.exception.ConflictException;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.common.exception.RoomFullException;
import com.codealpha.meetapp.room.dto.*;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final int MAX_HISTORY_LIMIT = 50;

    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final UserRepository userRepository;
    private final RoomCodeGenerator roomCodeGenerator;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;

    public RoomService(RoomRepository roomRepository,
                       RoomParticipantRepository roomParticipantRepository,
                       UserRepository userRepository,
                       RoomCodeGenerator roomCodeGenerator,
                       AppProperties appProperties,
                       ApplicationEventPublisher eventPublisher) {
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.userRepository = userRepository;
        this.roomCodeGenerator = roomCodeGenerator;
        this.appProperties = appProperties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Generates a unique room code and creates an active room record.
     * Decoupled host entry: the host is NOT automatically enrolled in room_participants
     * until they explicitly call joinRoom upon connecting to the call.
     */
    @Transactional
    public CreateRoomResponse createRoom(Long hostId) {
        User host = userRepository.findById(hostId)
                .orElseThrow(() -> new NotFoundException("Host user not found: " + hostId));

        String code = generateUniqueRoomCode();
        Room room = new Room(code, host);
        roomRepository.save(room);

        log.info("User {} created new room {}", hostId, code);
        return new CreateRoomResponse(room.getRoomCode(), host.getId(), room.isActive(), 0L, room.getCreatedAt());
    }

    /**
     * Joins a room under a pessimistic write lock to guarantee race-safe enforcement
     * of the room capacity limit (<= 5 active participants).
     */
    @Transactional
    public RoomResponse joinRoom(String roomCode, Long userId) {
        String normalizedCode = RoomCodeGenerator.normalizeCode(roomCode);
        Room room = roomRepository.findByRoomCodeForUpdate(normalizedCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + normalizedCode));

        if (!room.isActive()) {
            throw new ConflictException("Meeting room " + normalizedCode + " is no longer active.");
        }

        // Idempotent: If user already has an active participant record, return room without re-inserting
        if (roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), userId).isPresent()) {
            long count = roomParticipantRepository.countByRoomIdAndLeftAtIsNull(room.getId());
            return toRoomResponse(room, count);
        }

        // Capacity check
        long activeCount = roomParticipantRepository.countByRoomIdAndLeftAtIsNull(room.getId());
        if (activeCount >= appProperties.getMaxRoomParticipants()) {
            throw new RoomFullException("Meeting room is full (maximum " + appProperties.getMaxRoomParticipants() + " participants).");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        RoomParticipant participant = new RoomParticipant(room, user);
        roomParticipantRepository.save(participant);

        log.info("User {} successfully joined room {} ({}/{} participants)",
                userId, normalizedCode, activeCount + 1, appProperties.getMaxRoomParticipants());

        return toRoomResponse(room, activeCount + 1);
    }

    /**
     * Leaves an active room by setting left_at on the participant record.
     * When the host leaves, the meeting remains open for remaining participants.
     */
    @Transactional
    public MessageResponse leaveRoom(String roomCode, Long userId) {
        String normalizedCode = RoomCodeGenerator.normalizeCode(roomCode);
        Room room = roomRepository.findByRoomCode(normalizedCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + normalizedCode));

        roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), userId)
                .ifPresent(p -> {
                    p.setLeftAt(Instant.now());
                    roomParticipantRepository.save(p);
                    log.info("User {} left room {}", userId, normalizedCode);
                });

        return new MessageResponse("Left room " + normalizedCode);
    }

    /**
     * Terminates an active room. Only the meeting host can execute this.
     */
    @Transactional
    public MessageResponse endRoom(String roomCode, Long userId) {
        String normalizedCode = RoomCodeGenerator.normalizeCode(roomCode);
        Room room = roomRepository.findByRoomCode(normalizedCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + normalizedCode));

        if (!room.getHost().getId().equals(userId)) {
            throw new ForbiddenException("Only the room host can end the meeting.");
        }

        room.setActive(false);
        room.setEndedAt(Instant.now());
        roomRepository.save(room);

        // Mark all currently active participants departed
        List<RoomParticipant> activeParticipants = roomParticipantRepository.findByRoomIdAndLeftAtIsNull(room.getId());
        Instant now = Instant.now();
        for (RoomParticipant p : activeParticipants) {
            p.setLeftAt(now);
        }
        roomParticipantRepository.saveAll(activeParticipants);

        // Publish event to disconnect active WebSocket signaling sessions
        eventPublisher.publishEvent(new RoomEndedEvent(this, normalizedCode, userId));
        log.info("Host {} ended room {}", userId, normalizedCode);

        return new MessageResponse("Room " + normalizedCode + " ended successfully.");
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoom(String roomCode) {
        String normalizedCode = RoomCodeGenerator.normalizeCode(roomCode);
        Room room = roomRepository.findByRoomCode(normalizedCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + normalizedCode));

        long count = roomParticipantRepository.countByRoomIdAndLeftAtIsNull(room.getId());
        return toRoomResponse(room, count);
    }

    @Transactional(readOnly = true)
    public List<ParticipantResponse> getParticipants(String roomCode, Long userId) {
        String normalizedCode = RoomCodeGenerator.normalizeCode(roomCode);
        Room room = roomRepository.findByRoomCode(normalizedCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + normalizedCode));

        boolean isAuthorized = room.getHost().getId().equals(userId)
                || roomParticipantRepository.existsByRoomIdAndUserId(room.getId(), userId);

        if (!isAuthorized) {
            throw new ForbiddenException("Access denied: You are not a participant of this meeting.");
        }

        return roomParticipantRepository.findByRoomIdAndLeftAtIsNull(room.getId()).stream()
                .map(p -> new ParticipantResponse(p.getUser().getId(), p.getUser().getName(), p.getJoinedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserRoomHistoryItem> getUserRoomHistory(Long userId, Instant from, Instant to) {
        Instant effectiveFrom = from != null ? from : Instant.now().minus(7, ChronoUnit.DAYS);
        Instant effectiveTo = to != null ? to : Instant.now().plus(1, ChronoUnit.DAYS);

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new BadRequestException("The 'from' timestamp must be before or equal to 'to' timestamp.");
        }

        List<Room> rooms = roomRepository.findUserRooms(
                userId, effectiveFrom, effectiveTo, PageRequest.of(0, MAX_HISTORY_LIMIT)
        );

        return rooms.stream()
                .map(r -> {
                    String role = r.getHost().getId().equals(userId) ? "HOST" : "PARTICIPANT";
                    long count = roomParticipantRepository.countByRoomIdAndLeftAtIsNull(r.getId());
                    return new UserRoomHistoryItem(
                            r.getRoomCode(),
                            r.getHost().getId(),
                            role,
                            r.isActive(),
                            r.getCreatedAt(),
                            r.getEndedAt(),
                            count
                    );
                })
                .toList();
    }

    private String generateUniqueRoomCode() {
        for (int i = 0; i < 10; i++) {
            String code = roomCodeGenerator.generateCode();
            if (roomRepository.findByRoomCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Failed to generate a unique room code after multiple attempts.");
    }

    private RoomResponse toRoomResponse(Room room, long participantCount) {
        return new RoomResponse(
                room.getRoomCode(),
                room.getHost().getId(),
                room.isActive(),
                participantCount,
                appProperties.getMaxRoomParticipants(),
                room.getCreatedAt()
        );
    }
}

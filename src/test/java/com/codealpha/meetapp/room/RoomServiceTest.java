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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomParticipantRepository roomParticipantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoomCodeGenerator roomCodeGenerator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AppProperties appProperties;
    private RoomService roomService;

    private User hostUser;
    private User peerUser;
    private Room testRoom;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setMaxRoomParticipants(5);

        roomService = new RoomService(
                roomRepository,
                roomParticipantRepository,
                userRepository,
                roomCodeGenerator,
                appProperties,
                eventPublisher
        );

        hostUser = new User("Host User", "host@example.com", "hashed_pwd");
        setId(hostUser, 1L);

        peerUser = new User("Peer User", "peer@example.com", "hashed_pwd");
        setId(peerUser, 2L);

        testRoom = new Room("DEMO1001", hostUser);
        setId(testRoom, 10L);
    }

    private void setId(Object target, Long id) {
        try {
            var field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("createRoom generates unique code and creates room with decoupled host entry (0 participants)")
    void createRoom_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(hostUser));
        when(roomCodeGenerator.generateCode()).thenReturn("DEMO1001");
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateRoomResponse response = roomService.createRoom(1L);

        assertThat(response.roomCode()).isEqualTo("DEMO1001");
        assertThat(response.hostId()).isEqualTo(1L);
        assertThat(response.isActive()).isTrue();
        assertThat(response.participantCount()).isEqualTo(0L);

        verify(roomParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("joinRoom saves participant record and returns updated count")
    void joinRoom_success() {
        when(roomRepository.findByRoomCodeForUpdate("DEMO1001")).thenReturn(Optional.of(testRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 2L)).thenReturn(Optional.empty());
        when(roomParticipantRepository.countByRoomIdAndLeftAtIsNull(10L)).thenReturn(1L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(peerUser));

        RoomResponse response = roomService.joinRoom("demo1001", 2L);

        assertThat(response.roomCode()).isEqualTo("DEMO1001");
        assertThat(response.participantCount()).isEqualTo(2L);
        verify(roomParticipantRepository).save(any(RoomParticipant.class));
    }

    @Test
    @DisplayName("joinRoom is idempotent if user is already an active participant")
    void joinRoom_idempotent() {
        RoomParticipant existing = new RoomParticipant(testRoom, peerUser);
        when(roomRepository.findByRoomCodeForUpdate("DEMO1001")).thenReturn(Optional.of(testRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 2L)).thenReturn(Optional.of(existing));
        when(roomParticipantRepository.countByRoomIdAndLeftAtIsNull(10L)).thenReturn(2L);

        RoomResponse response = roomService.joinRoom("DEMO1001", 2L);

        assertThat(response.participantCount()).isEqualTo(2L);
        verify(roomParticipantRepository, never()).save(any(RoomParticipant.class));
    }

    @Test
    @DisplayName("joinRoom throws RoomFullException when capacity reaches limit")
    void joinRoom_throwsWhenFull() {
        when(roomRepository.findByRoomCodeForUpdate("DEMO1001")).thenReturn(Optional.of(testRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 2L)).thenReturn(Optional.empty());
        when(roomParticipantRepository.countByRoomIdAndLeftAtIsNull(10L)).thenReturn(5L);

        assertThatThrownBy(() -> roomService.joinRoom("DEMO1001", 2L))
                .isInstanceOf(RoomFullException.class)
                .hasMessageContaining("Meeting room is full");
    }

    @Test
    @DisplayName("joinRoom throws ConflictException when room is inactive")
    void joinRoom_throwsWhenInactive() {
        testRoom.setActive(false);
        when(roomRepository.findByRoomCodeForUpdate("DEMO1001")).thenReturn(Optional.of(testRoom));

        assertThatThrownBy(() -> roomService.joinRoom("DEMO1001", 2L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    @DisplayName("leaveRoom sets left_at and host leaving keeps room active")
    void leaveRoom_hostLeaves_roomStaysOpen() {
        RoomParticipant hostParticipant = new RoomParticipant(testRoom, hostUser);
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(testRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 1L)).thenReturn(Optional.of(hostParticipant));

        MessageResponse res = roomService.leaveRoom("DEMO1001", 1L);

        assertThat(res.message()).contains("Left room");
        assertThat(hostParticipant.getLeftAt()).isNotNull();
        assertThat(testRoom.isActive()).isTrue();
        verify(roomParticipantRepository).save(hostParticipant);
    }

    @Test
    @DisplayName("endRoom marks room and participants ended, and publishes RoomEndedEvent")
    void endRoom_hostEnds_publishesEvent() {
        RoomParticipant p1 = new RoomParticipant(testRoom, peerUser);
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(testRoom));
        when(roomParticipantRepository.findByRoomIdAndLeftAtIsNull(10L)).thenReturn(List.of(p1));

        MessageResponse res = roomService.endRoom("DEMO1001", 1L);

        assertThat(res.message()).contains("ended successfully");
        assertThat(testRoom.isActive()).isFalse();
        assertThat(testRoom.getEndedAt()).isNotNull();
        assertThat(p1.getLeftAt()).isNotNull();

        var eventCaptor = ArgumentCaptor.forClass(RoomEndedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRoomCode()).isEqualTo("DEMO1001");
        assertThat(eventCaptor.getValue().getHostId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("endRoom throws ForbiddenException when non-host attempts to end room")
    void endRoom_nonHost_throwsForbidden() {
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(testRoom));

        assertThatThrownBy(() -> roomService.endRoom("DEMO1001", 2L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the room host can end the meeting");
    }

    @Test
    @DisplayName("getParticipants returns list for enrolled participant and throws Forbidden for outsider")
    void getParticipants_permissions() {
        RoomParticipant p1 = new RoomParticipant(testRoom, peerUser);
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(testRoom));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 2L)).thenReturn(true);
        when(roomParticipantRepository.findByRoomIdAndLeftAtIsNull(10L)).thenReturn(List.of(p1));

        List<ParticipantResponse> list = roomService.getParticipants("DEMO1001", 2L);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).userId()).isEqualTo(2L);

        // Outsider
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 999L)).thenReturn(false);
        assertThatThrownBy(() -> roomService.getParticipants("DEMO1001", 999L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("getUserRoomHistory queries date range and enforces from <= to")
    void getUserRoomHistory_success() {
        Instant from = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant to = Instant.now();

        when(roomRepository.findUserRooms(eq(1L), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(List.of(testRoom));
        when(roomParticipantRepository.countByRoomIdAndLeftAtIsNull(10L)).thenReturn(1L);

        List<UserRoomHistoryItem> history = roomService.getUserRoomHistory(1L, from, to);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).roomCode()).isEqualTo("DEMO1001");
        assertThat(history.get(0).role()).isEqualTo("HOST");

        // Invalid date range
        assertThatThrownBy(() -> roomService.getUserRoomHistory(1L, to, from))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("before or equal");
    }
}

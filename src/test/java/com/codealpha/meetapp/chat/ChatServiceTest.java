package com.codealpha.meetapp.chat;

import com.codealpha.meetapp.chat.dto.ChatHistoryResponse;
import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.crypto.CryptoException;
import com.codealpha.meetapp.crypto.EncryptionService;
import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomParticipant;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomParticipantRepository roomParticipantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EncryptionService encryptionService;

    private AppProperties appProperties;
    private ChatService chatService;

    private Room activeRoom;
    private User testUser;
    private RoomParticipant activeParticipant;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setChatRetentionDays(30);

        chatService = new ChatService(
                chatMessageRepository,
                roomRepository,
                roomParticipantRepository,
                userRepository,
                encryptionService,
                appProperties
        );

        testUser = new User("Aarav Patel", "aarav@example.com", "hash");
        ReflectionTestUtils.setField(testUser, "id", 1L);

        activeRoom = new Room("DEMO1001", testUser);
        ReflectionTestUtils.setField(activeRoom, "id", 10L);
        activeRoom.setActive(true);

        activeParticipant = new RoomParticipant(activeRoom, testUser);
        ReflectionTestUtils.setField(activeParticipant, "id", 100L);
    }

    @Test
    @DisplayName("saveMessage: valid message encrypted and saved; returns persisted entity")
    void saveMessage_success() {
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 1L))
                .thenReturn(Optional.of(activeParticipant));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(encryptionService.encrypt("Hello world", 10L)).thenReturn("encrypted-ciphertext-base64");

        ChatMessage persisted = new ChatMessage(activeRoom, testUser, "encrypted-ciphertext-base64");
        ReflectionTestUtils.setField(persisted, "id", 42L);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(persisted);

        ChatMessage result = chatService.saveMessage("DEMO1001", 1L, "  Hello world  ");

        assertNotNull(result);
        assertEquals(42L, result.getId());
        verify(encryptionService).encrypt("Hello world", 10L);
        verify(chatMessageRepository).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("saveMessage: empty or whitespace message throws IllegalArgumentException")
    void saveMessage_emptyText_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage("DEMO1001", 1L, ""));
        assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage("DEMO1001", 1L, "   \n\t  "));
    }

    @Test
    @DisplayName("saveMessage: text exceeding 1000 characters throws IllegalArgumentException")
    void saveMessage_oversizedText_throwsException() {
        String oversized = "A".repeat(1001);
        assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage("DEMO1001", 1L, oversized));
    }

    @Test
    @DisplayName("saveMessage: text exceeding 20 lines throws IllegalArgumentException")
    void saveMessage_tooManyLines_throwsException() {
        String multiLine = "Line\n".repeat(21);
        assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage("DEMO1001", 1L, multiLine));
    }

    @Test
    @DisplayName("saveMessage: text containing non-newline control characters throws IllegalArgumentException")
    void saveMessage_illegalControlChars_throwsException() {
        String withNullByte = "Hello \u0000 World";
        assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage("DEMO1001", 1L, withNullByte));
    }

    @Test
    @DisplayName("saveMessage: inactive room throws IllegalArgumentException")
    void saveMessage_inactiveRoom_throwsException() {
        activeRoom.setActive(false);
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(activeRoom));

        assertThrows(IllegalArgumentException.class, () -> chatService.saveMessage("DEMO1001", 1L, "Hello"));
    }

    @Test
    @DisplayName("saveMessage: non-participant throws ForbiddenException")
    void saveMessage_nonParticipant_throwsForbidden() {
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 2L)).thenReturn(Optional.empty());

        assertThrows(ForbiddenException.class, () -> chatService.saveMessage("DEMO1001", 2L, "Hello"));
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("getHistory: returns decrypted messages in chronological oldest-first order")
    void getHistory_success_oldestFirst() {
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 1L)).thenReturn(true);

        ChatMessage msg1 = new ChatMessage(activeRoom, testUser, "cipher-1");
        ReflectionTestUtils.setField(msg1, "id", 101L);
        ChatMessage msg2 = new ChatMessage(activeRoom, testUser, "cipher-2");
        ReflectionTestUtils.setField(msg2, "id", 102L);

        // Repository returns id DESC (newest first: 102, then 101)
        when(chatMessageRepository.findRecentMessagesWithCursor(eq(10L), isNull(), any(Pageable.class)))
                .thenReturn(List.of(msg2, msg1));

        when(encryptionService.decrypt("cipher-1", 10L)).thenReturn("First message");
        when(encryptionService.decrypt("cipher-2", 10L)).thenReturn("Second message");

        List<ChatHistoryResponse> history = chatService.getHistory("DEMO1001", 1L, 50, null);

        assertEquals(2, history.size());
        // Service reverses to oldest-first: 101, then 102
        assertEquals(101L, history.get(0).id());
        assertEquals("First message", history.get(0).text());
        assertEquals(102L, history.get(1).id());
        assertEquals("Second message", history.get(1).text());
    }

    @Test
    @DisplayName("getHistory: tampered message decrypts safely to [Message unavailable] without crashing")
    void getHistory_tamperedCiphertext_handledGracefully() {
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 1L)).thenReturn(true);

        ChatMessage tampered = new ChatMessage(activeRoom, testUser, "corrupted-ciphertext");
        ReflectionTestUtils.setField(tampered, "id", 103L);

        when(chatMessageRepository.findRecentMessagesWithCursor(eq(10L), isNull(), any(Pageable.class)))
                .thenReturn(List.of(tampered));
        when(encryptionService.decrypt("corrupted-ciphertext", 10L))
                .thenThrow(new CryptoException("Tag mismatch"));

        List<ChatHistoryResponse> history = chatService.getHistory("DEMO1001", 1L, 50, null);

        assertEquals(1, history.size());
        assertEquals("[Message unavailable]", history.get(0).text());
    }

    @Test
    @DisplayName("getHistory: outsider who never joined room throws ForbiddenException")
    void getHistory_outsider_throwsForbidden() {
        when(roomRepository.findByRoomCode("DEMO1001")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 999L)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> chatService.getHistory("DEMO1001", 999L, 50, null));
    }

    @Test
    @DisplayName("getHistory: invalid limit (<1 or >100) or negative before cursor throws IllegalArgumentException")
    void getHistory_invalidPaginationParams_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> chatService.getHistory("DEMO1001", 1L, 0, null));
        assertThrows(IllegalArgumentException.class, () -> chatService.getHistory("DEMO1001", 1L, 101, null));
        assertThrows(IllegalArgumentException.class, () -> chatService.getHistory("DEMO1001", 1L, 50, -5L));
    }

    @Test
    @DisplayName("pruneOldMessages: deletes expired messages in batches")
    void pruneOldMessages_batchesCleanup() {
        // First batch returns 100 IDs, second batch returns 20 IDs, third returns empty
        when(chatMessageRepository.findRetentionCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L, 3L))
                .thenReturn(List.of());

        when(chatMessageRepository.deleteByIdIn(anyList())).thenReturn(3);

        int totalDeleted = chatService.pruneOldMessages();

        assertEquals(3, totalDeleted);
        verify(chatMessageRepository, times(1)).deleteByIdIn(anyList());
    }
}

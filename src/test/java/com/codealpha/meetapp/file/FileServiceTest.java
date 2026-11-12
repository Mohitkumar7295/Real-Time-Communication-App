package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.BadRequestException;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.crypto.EncryptionService;
import com.codealpha.meetapp.file.dto.FileListResponse;
import com.codealpha.meetapp.file.dto.FileUploadResponse;
import com.codealpha.meetapp.file.storage.StorageService;
import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomParticipant;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    private FileMetaRepository fileMetaRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomParticipantRepository roomParticipantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private FileValidator fileValidator;

    @Mock
    private RoomSessionRegistry roomSessionRegistry;

    private AppProperties appProperties;
    private FileService fileService;

    private User hostUser;
    private User participantUser;
    private User outsiderUser;
    private Room activeRoom;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setMaxRoomFiles(20);
        appProperties.setMaxRoomStorageMb(50);
        appProperties.setFileRetentionDays(7);

        fileService = new FileService(
                fileMetaRepository,
                roomRepository,
                roomParticipantRepository,
                userRepository,
                storageService,
                encryptionService,
                fileValidator,
                roomSessionRegistry,
                appProperties
        );

        hostUser = new User("Host User", "host@example.com", "hash");
        ReflectionTestUtils.setField(hostUser, "id", 1L);

        participantUser = new User("Participant User", "part@example.com", "hash");
        ReflectionTestUtils.setField(participantUser, "id", 2L);

        outsiderUser = new User("Outsider User", "out@example.com", "hash");
        ReflectionTestUtils.setField(outsiderUser, "id", 99L);

        activeRoom = new Room("TEST101", hostUser);
        ReflectionTestUtils.setField(activeRoom, "id", 10L);
    }

    @Test
    @DisplayName("uploadFile: successfully uploads, encrypts, persists, and broadcasts file-shared")
    void uploadFile_success() {
        MockMultipartFile file = new MockMultipartFile("file", "spec.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        when(roomRepository.findByRoomCodeForUpdate("TEST101")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 2L))
                .thenReturn(Optional.of(new RoomParticipant(activeRoom, participantUser)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(participantUser));
        when(fileMetaRepository.countByRoomId(10L)).thenReturn(5L);
        when(fileMetaRepository.sumSizeBytesByRoomId(10L)).thenReturn(1000L);

        when(fileValidator.validate(eq("spec.pdf"), any())).thenReturn(
                new FileValidator.ValidatedFileInfo("spec.pdf", "random-uuid.pdf", "application/pdf", 8L)
        );

        when(fileMetaRepository.saveAndFlush(any(FileMeta.class))).thenAnswer(inv -> {
            FileMeta m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", 501L);
            return m;
        });

        when(encryptionService.encryptFile(any(), eq(10L), eq(501L))).thenReturn(new byte[]{0x01, 0x02});

        FileUploadResponse response = fileService.uploadFile("TEST101", 2L, file);

        assertNotNull(response);
        assertEquals(501L, response.id());
        assertEquals("spec.pdf", response.originalName());
        assertEquals(2L, response.uploaderId());

        verify(storageService).store(eq(new byte[]{0x01, 0x02}), eq("random-uuid.pdf"));
        verify(roomSessionRegistry).broadcast(eq("TEST101"), isNull(), any(SignalMessage.class));
    }

    @Test
    @DisplayName("uploadFile: throws BadRequestException when room is ended")
    void uploadFile_roomEnded_throwsBadRequest() {
        activeRoom.setActive(false);
        activeRoom.setEndedAt(Instant.now());
        when(roomRepository.findByRoomCodeForUpdate("TEST101")).thenReturn(Optional.of(activeRoom));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes());
        assertThrows(BadRequestException.class, () -> fileService.uploadFile("TEST101", 2L, file));
    }

    @Test
    @DisplayName("uploadFile: throws ForbiddenException when caller is not room participant")
    void uploadFile_notParticipant_throwsForbidden() {
        when(roomRepository.findByRoomCodeForUpdate("TEST101")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 99L)).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes());
        assertThrows(ForbiddenException.class, () -> fileService.uploadFile("TEST101", 99L, file));
    }

    @Test
    @DisplayName("uploadFile: throws BadRequestException when room file count quota (20) exceeded")
    void uploadFile_countQuotaExceeded_throwsBadRequest() {
        when(roomRepository.findByRoomCodeForUpdate("TEST101")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 2L))
                .thenReturn(Optional.of(new RoomParticipant(activeRoom, participantUser)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(participantUser));
        when(fileMetaRepository.countByRoomId(10L)).thenReturn(20L); // Limit is 20

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "data".getBytes());
        assertThrows(BadRequestException.class, () -> fileService.uploadFile("TEST101", 2L, file));
    }

    @Test
    @DisplayName("uploadFile: throws BadRequestException when room storage quota (50 MB) exceeded")
    void uploadFile_storageQuotaExceeded_throwsBadRequest() {
        when(roomRepository.findByRoomCodeForUpdate("TEST101")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(10L, 2L))
                .thenReturn(Optional.of(new RoomParticipant(activeRoom, participantUser)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(participantUser));
        when(fileMetaRepository.countByRoomId(10L)).thenReturn(5L);
        // Current storage is 50MB
        long fiftyMb = 50L * 1024 * 1024;
        when(fileMetaRepository.sumSizeBytesByRoomId(10L)).thenReturn(fiftyMb);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[100]);
        assertThrows(BadRequestException.class, () -> fileService.uploadFile("TEST101", 2L, file));
    }

    @Test
    @DisplayName("getRoomFiles: returns paged file list with hasMore")
    void getRoomFiles_success() {
        when(roomRepository.findByRoomCode("TEST101")).thenReturn(Optional.of(activeRoom));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 2L)).thenReturn(true);

        FileMeta meta1 = new FileMeta(activeRoom, participantUser, "a.pdf", "a.pdf", "application/pdf", 100L);
        ReflectionTestUtils.setField(meta1, "id", 101L);
        FileMeta meta2 = new FileMeta(activeRoom, participantUser, "b.pdf", "b.pdf", "application/pdf", 200L);
        ReflectionTestUtils.setField(meta2, "id", 100L);

        when(fileMetaRepository.findRecentFilesWithCursor(eq(10L), isNull(), any(Pageable.class)))
                .thenReturn(List.of(meta1, meta2));

        FileListResponse response = fileService.getRoomFiles("TEST101", 2L, null, 10);
        assertEquals(2, response.files().size());
        assertFalse(response.hasMore());
    }

    @Test
    @DisplayName("loadFileForDownload: outsider receives 404 Not Found (enumeration defense)")
    void loadFileForDownload_outsider_throwsNotFound() {
        FileMeta meta = new FileMeta(activeRoom, participantUser, "test.pdf", "test.pdf", "application/pdf", 100L);
        ReflectionTestUtils.setField(meta, "id", 201L);

        when(fileMetaRepository.findById(201L)).thenReturn(Optional.of(meta));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 99L)).thenReturn(false);

        NotFoundException ex = assertThrows(NotFoundException.class, () ->
                fileService.loadFileForDownload(201L, 99L)
        );
        assertEquals("File not found: 201", ex.getMessage());
    }

    @Test
    @DisplayName("loadFileForDownload: participant successfully decrypts and downloads")
    void loadFileForDownload_success() {
        FileMeta meta = new FileMeta(activeRoom, participantUser, "test.pdf", "uuid.pdf", "application/pdf", 100L);
        ReflectionTestUtils.setField(meta, "id", 201L);

        when(fileMetaRepository.findById(201L)).thenReturn(Optional.of(meta));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 2L)).thenReturn(true);

        byte[] encrypted = new byte[]{0x01, 0x02};
        byte[] decrypted = "%PDF-content".getBytes(StandardCharsets.US_ASCII);

        when(storageService.loadAsBytes("uuid.pdf")).thenReturn(encrypted);
        when(encryptionService.decryptFile(encrypted, 10L, 201L)).thenReturn(decrypted);

        FileService.DownloadedFile result = fileService.loadFileForDownload(201L, 2L);
        assertNotNull(result);
        assertArrayEquals(decrypted, result.plaintextBytes());
    }

    @Test
    @DisplayName("deleteFile: uploader successfully deletes and broadcasts file-deleted")
    void deleteFile_byUploader_success() {
        FileMeta meta = new FileMeta(activeRoom, participantUser, "test.pdf", "uuid.pdf", "application/pdf", 100L);
        ReflectionTestUtils.setField(meta, "id", 301L);

        when(fileMetaRepository.findById(301L)).thenReturn(Optional.of(meta));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 2L)).thenReturn(true);

        fileService.deleteFile(301L, 2L);

        verify(storageService).delete("uuid.pdf");
        verify(fileMetaRepository).delete(meta);

        ArgumentCaptor<SignalMessage> captor = ArgumentCaptor.forClass(SignalMessage.class);
        verify(roomSessionRegistry).broadcast(eq("TEST101"), isNull(), captor.capture());
        assertEquals(MessageType.FILE_DELETED, captor.getValue().getType());
    }

    @Test
    @DisplayName("deleteFile: host successfully deletes other participant's file")
    void deleteFile_byHost_success() {
        FileMeta meta = new FileMeta(activeRoom, participantUser, "test.pdf", "uuid.pdf", "application/pdf", 100L);
        ReflectionTestUtils.setField(meta, "id", 302L);

        when(fileMetaRepository.findById(302L)).thenReturn(Optional.of(meta));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 1L)).thenReturn(true);

        fileService.deleteFile(302L, 1L); // Caller is host (id=1)

        verify(storageService).delete("uuid.pdf");
        verify(fileMetaRepository).delete(meta);
    }

    @Test
    @DisplayName("deleteFile: other participant receiving 403 Forbidden")
    void deleteFile_byOtherParticipant_throwsForbidden() {
        User otherParticipant = new User("Other", "other@example.com", "hash");
        ReflectionTestUtils.setField(otherParticipant, "id", 3L);

        FileMeta meta = new FileMeta(activeRoom, participantUser, "test.pdf", "uuid.pdf", "application/pdf", 100L);
        ReflectionTestUtils.setField(meta, "id", 303L);

        when(fileMetaRepository.findById(303L)).thenReturn(Optional.of(meta));
        when(roomParticipantRepository.existsByRoomIdAndUserId(10L, 3L)).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> fileService.deleteFile(303L, 3L));
        verify(storageService, never()).delete(anyString());
    }

    @Test
    @DisplayName("deleteUserFiles: deletes stored files for user's uploaded files")
    void deleteUserFiles_success() {
        FileMeta meta1 = new FileMeta(activeRoom, participantUser, "a.pdf", "uuidA.pdf", "application/pdf", 100L);
        FileMeta meta2 = new FileMeta(activeRoom, participantUser, "b.pdf", "uuidB.pdf", "application/pdf", 200L);

        when(fileMetaRepository.findByUploaderId(2L)).thenReturn(List.of(meta1, meta2));

        fileService.deleteUserFiles(2L);

        verify(storageService).delete("uuidA.pdf");
        verify(storageService).delete("uuidB.pdf");
    }

    @Test
    @DisplayName("pruneRetentionFiles: removes expired room files and orphaned disk files")
    void pruneRetentionFiles_success() {
        FileMeta expired = new FileMeta(activeRoom, participantUser, "old.pdf", "oldUuid.pdf", "application/pdf", 100L);
        ReflectionTestUtils.setField(expired, "id", 999L);

        when(fileMetaRepository.findRetentionCandidates(any(), any())).thenReturn(List.of(expired));
        when(storageService.listAllStoredFiles()).thenReturn(List.of("oldUuid.pdf", "orphanUuid.pdf"));
        when(fileMetaRepository.existsByStoredName("oldUuid.pdf")).thenReturn(true);
        when(fileMetaRepository.existsByStoredName("orphanUuid.pdf")).thenReturn(false);

        fileService.pruneRetentionFiles();

        verify(storageService).delete("oldUuid.pdf");
        verify(fileMetaRepository).deleteByIdIn(List.of(999L));
        verify(storageService).delete("orphanUuid.pdf");
    }
}

package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.BadRequestException;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.crypto.EncryptionService;
import com.codealpha.meetapp.file.dto.FileItemDto;
import com.codealpha.meetapp.file.dto.FileListResponse;
import com.codealpha.meetapp.file.dto.FileUploadResponse;
import com.codealpha.meetapp.file.storage.StorageService;
import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.signaling.dto.FileDeletedPayload;
import com.codealpha.meetapp.signaling.dto.FileSharedPayload;
import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Core business service managing file uploads, downloads, listings, deletions,
 * encrypted storage on disk with AES-256-GCM, and automated retention cleanup.
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final int RETENTION_BATCH_SIZE = 100;

    public record DownloadedFile(FileMeta meta, byte[] plaintextBytes) {}

    private final FileMetaRepository fileMetaRepository;
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final EncryptionService encryptionService;
    private final FileValidator fileValidator;
    private final RoomSessionRegistry roomSessionRegistry;
    private final AppProperties appProperties;

    public FileService(FileMetaRepository fileMetaRepository,
                       RoomRepository roomRepository,
                       RoomParticipantRepository roomParticipantRepository,
                       UserRepository userRepository,
                       StorageService storageService,
                       EncryptionService encryptionService,
                       FileValidator fileValidator,
                       RoomSessionRegistry roomSessionRegistry,
                       AppProperties appProperties) {
        this.fileMetaRepository = fileMetaRepository;
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.encryptionService = encryptionService;
        this.fileValidator = fileValidator;
        this.roomSessionRegistry = roomSessionRegistry;
        this.appProperties = appProperties;
    }

    /**
     * Uploads, validates, encrypts, and stores a file in a room.
     * Enforces race-safe quotas via pessimistic locking on the Room row.
     */
    @Transactional
    public FileUploadResponse uploadFile(String roomCode, Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Uploaded file is empty");
        }

        // 1. Lock room row to serialize concurrent uploads in this room
        Room room = roomRepository.findByRoomCodeForUpdate(roomCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + roomCode));

        if (!room.isActive()) {
            throw new BadRequestException("Cannot upload files to an ended room");
        }

        // 2. Caller must be an active participant in this room
        boolean isParticipant = roomParticipantRepository.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), userId).isPresent();
        if (!isParticipant) {
            throw new ForbiddenException("User is not a participant in this room");
        }

        User uploader = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Failed to read uploaded file payload");
        }

        // 3. Quota enforcement
        long currentCount = fileMetaRepository.countByRoomId(room.getId());
        if (currentCount >= appProperties.getMaxRoomFiles()) {
            throw new BadRequestException("Room file limit exceeded (maximum " + appProperties.getMaxRoomFiles() + " files)");
        }

        long currentTotalBytes = fileMetaRepository.sumSizeBytesByRoomId(room.getId());
        long maxStorageBytes = (long) appProperties.getMaxRoomStorageMb() * 1024 * 1024;
        if (currentTotalBytes + rawBytes.length > maxStorageBytes) {
            throw new BadRequestException("Room storage quota exceeded (maximum " + appProperties.getMaxRoomStorageMb() + " MB)");
        }

        // 4. Validate extension, blocklist, multi-extension heuristics, and magic bytes
        FileValidator.ValidatedFileInfo validated = fileValidator.validate(file.getOriginalFilename(), rawBytes);

        // 5. Persist FileMeta in database to acquire entity ID
        FileMeta fileMeta = new FileMeta(
                room,
                uploader,
                validated.originalName(),
                validated.storedName(),
                validated.contentType(),
                validated.sizeBytes()
        );
        fileMeta = fileMetaRepository.saveAndFlush(fileMeta);

        // 6. Encrypt with AES-256-GCM binding (roomId, fileId) as AAD
        byte[] encryptedBytes;
        try {
            encryptedBytes = encryptionService.encryptFile(rawBytes, room.getId(), fileMeta.getId());
        } catch (Exception e) {
            log.error("Failed to encrypt uploaded file for room {}", roomCode, e);
            throw new RuntimeException("File encryption failed", e);
        }

        // 7. Store encrypted payload atomically on disk
        try {
            storageService.store(encryptedBytes, fileMeta.getStoredName());
        } catch (Exception e) {
            log.error("Failed to persist file to storage: {}", fileMeta.getStoredName(), e);
            throw new RuntimeException("Storage failure", e);
        }

        // 8. Broadcast file-shared signaling event to all room participants
        FileSharedPayload sharedPayload = new FileSharedPayload(
                fileMeta.getId(),
                fileMeta.getOriginalName(),
                fileMeta.getSizeBytes(),
                uploader.getId(),
                uploader.getName(),
                fileMeta.getUploadedAt()
        );
        SignalMessage broadcastMessage = SignalMessage.of(MessageType.FILE_SHARED, roomCode, sharedPayload);
        roomSessionRegistry.broadcast(roomCode, null, broadcastMessage);

        log.info("File id {} ('{}') successfully uploaded to room {} by user {}",
                fileMeta.getId(), fileMeta.getOriginalName(), roomCode, userId);

        return new FileUploadResponse(
                fileMeta.getId(),
                fileMeta.getOriginalName(),
                fileMeta.getSizeBytes(),
                fileMeta.getContentType(),
                uploader.getId(),
                uploader.getName(),
                fileMeta.getUploadedAt()
        );
    }

    /**
     * Lists files for a room with cursor-based pagination.
     */
    @Transactional(readOnly = true)
    public FileListResponse getRoomFiles(String roomCode, Long userId, Long beforeId, int limit) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + roomCode));

        boolean isParticipant = roomParticipantRepository.existsByRoomIdAndUserId(room.getId(), userId);
        if (!isParticipant) {
            throw new ForbiddenException("User is not a participant in this room");
        }

        if (limit < 1 || limit > 100) {
            throw new BadRequestException("limit must be between 1 and 100");
        }
        if (beforeId != null && beforeId <= 0) {
            throw new BadRequestException("before must be a positive integer");
        }

        PageRequest pageRequest = PageRequest.of(0, limit + 1);
        List<FileMeta> fetched = fileMetaRepository.findRecentFilesWithCursor(room.getId(), beforeId, pageRequest);

        boolean hasMore = fetched.size() > limit;
        List<FileMeta> subList = hasMore ? fetched.subList(0, limit) : fetched;

        List<FileItemDto> items = subList.stream()
                .map(f -> new FileItemDto(
                        f.getId(),
                        f.getOriginalName(),
                        f.getSizeBytes(),
                        f.getContentType(),
                        f.getUploader().getId(),
                        f.getUploader().getName(),
                        f.getUploadedAt()
                ))
                .toList();

        return new FileListResponse(items, hasMore);
    }

    /**
     * Loads and decrypts file content for download.
     * Throws 404 for both non-existent files and non-participants to prevent enumeration.
     */
    @Transactional(readOnly = true)
    public DownloadedFile loadFileForDownload(Long fileId, Long userId) {
        FileMeta fileMeta = fileMetaRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("File not found: " + fileId));

        boolean isParticipant = roomParticipantRepository.existsByRoomIdAndUserId(fileMeta.getRoom().getId(), userId);
        if (!isParticipant) {
            // Outsider probing defense: identical 404 to non-existent file
            throw new NotFoundException("File not found: " + fileId);
        }

        byte[] encryptedBytes = storageService.loadAsBytes(fileMeta.getStoredName());
        byte[] decryptedBytes = encryptionService.decryptFile(encryptedBytes, fileMeta.getRoom().getId(), fileMeta.getId());

        return new DownloadedFile(fileMeta, decryptedBytes);
    }

    /**
     * Deletes a file from storage and database.
     * Allowed only for the file uploader or room host.
     */
    @Transactional
    public void deleteFile(Long fileId, Long userId) {
        FileMeta fileMeta = fileMetaRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("File not found: " + fileId));

        Room room = fileMeta.getRoom();
        boolean isParticipant = roomParticipantRepository.existsByRoomIdAndUserId(room.getId(), userId);
        if (!isParticipant) {
            throw new NotFoundException("File not found: " + fileId);
        }

        boolean isUploader = fileMeta.getUploader().getId().equals(userId);
        boolean isHost = room.getHost().getId().equals(userId);
        if (!isUploader && !isHost) {
            throw new ForbiddenException("Only the file uploader or the room host may delete this file");
        }

        // Delete from disk storage
        storageService.delete(fileMeta.getStoredName());

        // Delete from database
        fileMetaRepository.delete(fileMeta);

        // Broadcast file-deleted signaling message
        SignalMessage broadcastMessage = SignalMessage.of(
                MessageType.FILE_DELETED,
                room.getRoomCode(),
                new FileDeletedPayload(fileId)
        );
        roomSessionRegistry.broadcast(room.getRoomCode(), null, broadcastMessage);

        log.info("File id {} deleted from room {} by user {}", fileId, room.getRoomCode(), userId);
    }

    /**
     * Deletes physical stored files uploaded by a user before their account is purged.
     */
    @Transactional(readOnly = true)
    public void deleteUserFiles(Long userId) {
        List<FileMeta> userFiles = fileMetaRepository.findByUploaderId(userId);
        for (FileMeta f : userFiles) {
            storageService.delete(f.getStoredName());
        }
        log.info("Cleaned up {} stored physical files for user {}", userFiles.size(), userId);
    }

    /**
     * Batch prunes files belonging to rooms ended more than fileRetentionDays ago,
     * and deletes orphaned files on disk without corresponding database rows.
     */
    @Transactional
    public void pruneRetentionFiles() {
        Instant cutoff = Instant.now().minus(appProperties.getFileRetentionDays(), ChronoUnit.DAYS);
        PageRequest pageRequest = PageRequest.of(0, RETENTION_BATCH_SIZE);
        List<FileMeta> expired = fileMetaRepository.findRetentionCandidates(cutoff, pageRequest);

        if (!expired.isEmpty()) {
            for (FileMeta f : expired) {
                storageService.delete(f.getStoredName());
            }
            List<Long> ids = expired.stream().map(FileMeta::getId).toList();
            fileMetaRepository.deleteByIdIn(ids);
            log.info("Pruned {} expired file records older than {}", expired.size(), cutoff);
        }

        // Orphan disk file cleanup
        List<String> diskFiles = storageService.listAllStoredFiles();
        int orphanCount = 0;
        for (String storedName : diskFiles) {
            if (!fileMetaRepository.existsByStoredName(storedName)) {
                storageService.delete(storedName);
                orphanCount++;
            }
        }
        if (orphanCount > 0) {
            log.info("Cleaned up {} orphaned files from disk storage", orphanCount);
        }
    }
}

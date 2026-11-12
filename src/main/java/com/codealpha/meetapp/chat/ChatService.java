package com.codealpha.meetapp.chat;

import com.codealpha.meetapp.chat.dto.ChatHistoryResponse;
import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.ForbiddenException;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.crypto.CryptoException;
import com.codealpha.meetapp.crypto.EncryptionService;
import com.codealpha.meetapp.room.Room;
import com.codealpha.meetapp.room.RoomParticipantRepository;
import com.codealpha.meetapp.room.RoomRepository;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service managing in-room group chat persistence, cursor history retrieval,
 * AES-256-GCM encryption at rest with AAD binding, and retention pruning.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_TEXT_LENGTH = 1000;
    private static final int MAX_LINES = 20;
    private static final int BATCH_PRUNE_SIZE = 100;

    // Pattern matching illegal control characters except newline and carriage return
    private static final Pattern ILLEGAL_CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]");

    private final ChatMessageRepository chatMessageRepository;
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final AppProperties appProperties;

    public ChatService(ChatMessageRepository chatMessageRepository,
                       RoomRepository roomRepository,
                       RoomParticipantRepository roomParticipantRepository,
                       UserRepository userRepository,
                       EncryptionService encryptionService,
                       AppProperties appProperties) {
        this.chatMessageRepository = chatMessageRepository;
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.appProperties = appProperties;
    }

    /**
     * Validates, encrypts with AES-256-GCM (using roomId as AAD), and persists a chat message.
     * Enforces active participant membership. Never logs message text.
     */
    @Transactional
    public ChatMessage saveMessage(String roomCode, Long senderId, String rawText) {
        validateMessageText(rawText);

        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + roomCode));

        if (!room.isActive()) {
            throw new IllegalArgumentException("Cannot send messages to an inactive room");
        }

        // Verify sender is an active participant in this room
        boolean isActiveParticipant = roomParticipantRepository
                .findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), senderId)
                .isPresent();
        if (!isActiveParticipant) {
            throw new ForbiddenException("User is not an active participant in this room");
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new NotFoundException("Sender user not found: " + senderId));

        String trimmedText = rawText.trim();

        // Encrypt with AES-256-GCM using roomId as AAD
        String ciphertext = encryptionService.encrypt(trimmedText, room.getId());

        ChatMessage message = new ChatMessage(room, sender, ciphertext);
        message.setSentAt(Instant.now());
        ChatMessage saved = chatMessageRepository.save(message);

        log.info("Chat message saved: id={}, senderId={}, room={}", saved.getId(), senderId, roomCode);
        return saved;
    }

    /**
     * Loads chat history with cursor-based pagination.
     * Verifies that the caller has participated in the room (active or past).
     * Decrypts ciphertext with AAD, safely handling tampered content as "[Message unavailable]".
     * Returns messages ordered oldest-first.
     */
    @Transactional(readOnly = true)
    public List<ChatHistoryResponse> getHistory(String roomCode, Long requestingUserId, Integer limit, Long beforeId) {
        int pageLimit = (limit != null) ? limit : 50;
        if (pageLimit < 1 || pageLimit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (beforeId != null && beforeId <= 0) {
            throw new IllegalArgumentException("before must be a positive number");
        }

        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new NotFoundException("Room not found: " + roomCode));

        // Membership check: caller must be a current or past participant of this room
        boolean hasParticipated = roomParticipantRepository.existsByRoomIdAndUserId(room.getId(), requestingUserId);
        if (!hasParticipated) {
            throw new ForbiddenException("User has not participated in room: " + roomCode);
        }

        List<ChatMessage> entities = chatMessageRepository.findRecentMessagesWithCursor(
                room.getId(),
                beforeId,
                PageRequest.of(0, pageLimit)
        );

        List<ChatHistoryResponse> responseList = new ArrayList<>(entities.size());
        for (ChatMessage m : entities) {
            String decryptedText;
            try {
                decryptedText = encryptionService.decrypt(m.getContent(), room.getId());
            } catch (CryptoException e) {
                log.warn("Failed to decrypt message id {} in room {}: tampered ciphertext or wrong room AAD",
                        m.getId(), room.getId());
                decryptedText = "[Message unavailable]";
            }

            responseList.add(new ChatHistoryResponse(
                    m.getId(),
                    m.getSender().getId(),
                    m.getSender().getName(),
                    decryptedText,
                    m.getSentAt()
            ));
        }

        // Reverse to return chronological oldest-first order
        Collections.reverse(responseList);
        return responseList;
    }

    /**
     * Validates message constraints:
     * - Trimmed length between 1 and 1000 characters
     * - Maximum 20 lines
     * - No illegal control characters (except newline)
     */
    public void validateMessageText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Message text cannot be empty");
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Message text must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
        String[] lines = text.split("\r\n|\r|\n", -1);
        if (lines.length > MAX_LINES) {
            throw new IllegalArgumentException("Message must not exceed " + MAX_LINES + " lines");
        }
        if (ILLEGAL_CONTROL_CHARS.matcher(text).find()) {
            throw new IllegalArgumentException("Message contains illegal control characters");
        }
    }

    /**
     * Retention cleanup: executes daily at 3:00 AM UTC.
     * Deletes messages older than retentionDays (default 30) for rooms that are either
     * ended or have zero active participants. Deletes in small batches of 100 to avoid lock contention.
     */
    @Scheduled(cron = "${app.chat.retention-cron:0 0 3 * * ?}")
    @Transactional
    public int pruneOldMessages() {
        int retentionDays = appProperties.getChatRetentionDays();
        if (retentionDays <= 0) {
            log.info("Chat retention cleanup is disabled (retentionDays={})", retentionDays);
            return 0;
        }

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Starting chat retention cleanup for messages older than {} (cutoff={})",
                retentionDays + " days", cutoff);

        int totalDeleted = 0;
        while (true) {
            List<Long> candidateIds = chatMessageRepository.findRetentionCandidates(
                    cutoff,
                    PageRequest.of(0, BATCH_PRUNE_SIZE)
            );
            if (candidateIds.isEmpty()) {
                break;
            }
            int deleted = chatMessageRepository.deleteByIdIn(candidateIds);
            totalDeleted += deleted;
            if (candidateIds.size() < BATCH_PRUNE_SIZE) {
                break;
            }
        }

        log.info("Chat retention cleanup complete. Total messages pruned: {}", totalDeleted);
        return totalDeleted;
    }
}

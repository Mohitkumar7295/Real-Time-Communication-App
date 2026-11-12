package com.codealpha.meetapp.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * Retrieves recent messages for a room with cursor-based pagination (ordered id DESC).
     * JOIN FETCH ensures the sender entity is retrieved eagerly without N+1 query overhead.
     */
    @Query("SELECT m FROM ChatMessage m JOIN FETCH m.sender WHERE m.room.id = :roomId AND (:beforeId IS NULL OR m.id < :beforeId) ORDER BY m.id DESC")
    List<ChatMessage> findRecentMessagesWithCursor(@Param("roomId") Long roomId, @Param("beforeId") Long beforeId, Pageable pageable);

    /**
     * Finds message IDs eligible for retention cleanup:
     * Sent prior to cutoff timestamp AND belonging to rooms that are either inactive
     * or have zero active participants.
     */
    @Query("SELECT m.id FROM ChatMessage m WHERE m.sentAt < :cutoff AND (m.room.isActive = false OR (SELECT COUNT(p) FROM RoomParticipant p WHERE p.room = m.room AND p.leftAt IS NULL) = 0)")
    List<Long> findRetentionCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Deletes messages by ID in small batches.
     */
    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.id IN :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}

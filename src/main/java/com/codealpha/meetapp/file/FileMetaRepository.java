package com.codealpha.meetapp.file;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetaRepository extends JpaRepository<FileMeta, Long> {

    List<FileMeta> findByRoomIdOrderByUploadedAtDesc(Long roomId);

    Optional<FileMeta> findByStoredName(String storedName);

    boolean existsByStoredName(String storedName);

    @Query("SELECT f FROM FileMeta f JOIN FETCH f.uploader WHERE f.room.id = :roomId " +
           "AND (:beforeId IS NULL OR f.id < :beforeId) ORDER BY f.id DESC")
    List<FileMeta> findRecentFilesWithCursor(
            @Param("roomId") Long roomId,
            @Param("beforeId") Long beforeId,
            Pageable pageable
    );

    long countByRoomId(Long roomId);

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM FileMeta f WHERE f.room.id = :roomId")
    long sumSizeBytesByRoomId(@Param("roomId") Long roomId);

    List<FileMeta> findByUploaderId(Long uploaderId);

    @Query("SELECT f FROM FileMeta f JOIN FETCH f.room WHERE f.room.endedAt IS NOT NULL AND f.room.endedAt <= :cutoff ORDER BY f.id ASC")
    List<FileMeta> findRetentionCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying
    @Query("DELETE FROM FileMeta f WHERE f.id IN :ids")
    void deleteByIdIn(@Param("ids") List<Long> ids);
}

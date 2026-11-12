package com.codealpha.meetapp.room;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findByRoomCode(String roomCode);

    Optional<Room> findByRoomCodeAndIsActiveTrue(String roomCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.roomCode = :roomCode")
    Optional<Room> findByRoomCodeForUpdate(@Param("roomCode") String roomCode);

    @Query("SELECT DISTINCT r FROM Room r LEFT JOIN RoomParticipant rp ON rp.room.id = r.id WHERE (r.host.id = :userId OR rp.user.id = :userId) AND r.createdAt >= :from AND r.createdAt <= :to ORDER BY r.createdAt DESC")
    List<Room> findUserRooms(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query("SELECT r FROM Room r WHERE r.isActive = true AND NOT EXISTS (SELECT rp FROM RoomParticipant rp WHERE rp.room.id = r.id AND rp.leftAt IS NULL)")
    List<Room> findActiveRoomsWithNoActiveParticipants();
}

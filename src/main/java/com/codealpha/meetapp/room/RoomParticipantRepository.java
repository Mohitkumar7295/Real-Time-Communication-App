package com.codealpha.meetapp.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomParticipantRepository extends JpaRepository<RoomParticipant, Long> {

    long countByRoomIdAndLeftAtIsNull(Long roomId);

    Optional<RoomParticipant> findByRoomIdAndUserIdAndLeftAtIsNull(Long roomId, Long userId);

    List<RoomParticipant> findByRoomIdAndLeftAtIsNull(Long roomId);
}

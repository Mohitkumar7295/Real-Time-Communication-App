package com.codealpha.meetapp.room.dto;

import java.time.Instant;

public record RoomResponse(
        String roomCode,
        Long hostId,
        boolean isActive,
        long participantCount,
        int maxParticipants,
        Instant createdAt
) {
}

package com.codealpha.meetapp.room.dto;

import java.time.Instant;

public record CreateRoomResponse(
        String roomCode,
        Long hostId,
        boolean isActive,
        long participantCount,
        Instant createdAt
) {
}

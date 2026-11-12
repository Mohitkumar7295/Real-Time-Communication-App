package com.codealpha.meetapp.room.dto;

import java.time.Instant;

public record UserRoomHistoryItem(
        String roomCode,
        Long hostId,
        String role,
        boolean isActive,
        Instant createdAt,
        Instant endedAt,
        long participantCount
) {
}

package com.codealpha.meetapp.room.dto;

import java.time.Instant;

public record ParticipantResponse(
        Long userId,
        String name,
        Instant joinedAt
) {
}

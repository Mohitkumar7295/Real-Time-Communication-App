package com.codealpha.meetapp.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Authoritative outbound payload broadcast to all participants in the room,
 * including the original sender.
 */
public record ChatBroadcastPayload(
        Long id,
        Long from,
        String name,
        String text,
        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant sentAt,
        String clientId
) {
}

package com.codealpha.meetapp.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * REST response DTO for individual chat history entries.
 * Never exposes email addresses or internal system fields.
 */
public record ChatHistoryResponse(
        Long id,
        Long senderId,
        String senderName,
        String text,
        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant sentAt
) {
}

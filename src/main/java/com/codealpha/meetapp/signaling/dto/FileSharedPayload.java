package com.codealpha.meetapp.signaling.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Payload broadcast by server when a new file has been uploaded and committed.
 */
public record FileSharedPayload(
        Long fileId,
        String originalName,
        Long sizeBytes,
        Long uploaderId,
        String uploaderName,
        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant uploadedAt
) {
}

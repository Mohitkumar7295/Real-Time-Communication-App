package com.codealpha.meetapp.file.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Metadata representation of a file within a room file list.
 */
public record FileItemDto(
        Long id,
        String originalName,
        Long sizeBytes,
        String contentType,
        Long uploaderId,
        String uploaderName,
        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant uploadedAt
) {
}

package com.codealpha.meetapp.file.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * REST response returned upon successful file upload (HTTP 201 Created).
 */
public record FileUploadResponse(
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

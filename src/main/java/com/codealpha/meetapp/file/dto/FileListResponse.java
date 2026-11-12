package com.codealpha.meetapp.file.dto;

import java.util.List;

/**
 * Paged file list response returned by GET /api/rooms/{code}/files.
 */
public record FileListResponse(
        List<FileItemDto> files,
        boolean hasMore
) {
}

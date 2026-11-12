package com.codealpha.meetapp.signaling.dto;

/**
 * Payload broadcast by server when a file has been deleted from a room.
 */
public record FileDeletedPayload(
        Long fileId
) {
}

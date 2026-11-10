package com.codealpha.meetapp.common.exception;

/**
 * Thrown when a participant attempts to join a room that has reached capacity (docs/API.md).
 */
public class RoomFullException extends RuntimeException {

    public RoomFullException(String message) {
        super(message);
    }

    public RoomFullException() {
        super("Room has reached its maximum participant limit.");
    }
}

package com.codealpha.meetapp.whiteboard;

/**
 * Domain exception for whiteboard operations with specific signaling error codes.
 */
public class WhiteboardException extends RuntimeException {

    private final String errorCode;

    public WhiteboardException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

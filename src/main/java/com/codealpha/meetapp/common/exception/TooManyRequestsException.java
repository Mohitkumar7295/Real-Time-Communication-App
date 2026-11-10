package com.codealpha.meetapp.common.exception;

public class TooManyRequestsException extends RuntimeException {

    private final int retryAfterSeconds;

    public TooManyRequestsException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public TooManyRequestsException(String message) {
        this(message, 60);
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

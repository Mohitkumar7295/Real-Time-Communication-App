package com.codealpha.meetapp.common.exception;

/**
 * Thrown when an uploaded file's media type or extension is blocked or unsupported.
 * Results in HTTP 415 Unsupported Media Type.
 */
public class UnsupportedMediaTypeException extends RuntimeException {

    public UnsupportedMediaTypeException(String message) {
        super(message);
    }
}

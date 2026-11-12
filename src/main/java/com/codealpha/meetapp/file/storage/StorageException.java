package com.codealpha.meetapp.file.storage;

/**
 * Exception thrown when file storage operations (read, write, delete) fail.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.codealpha.meetapp.file.storage;

import java.io.InputStream;
import java.util.List;

/**
 * Storage service interface abstracting physical file storage.
 */
public interface StorageService {

    /**
     * Stores byte content atomically using the specified storedName.
     * Overwrites any existing file with the same name.
     */
    void store(byte[] bytes, String storedName);

    /**
     * Loads the entire binary file as a byte array into memory.
     */
    byte[] loadAsBytes(String storedName);

    /**
     * Deletes a file by storedName. Returns true if deleted, false if file did not exist.
     */
    boolean delete(String storedName);

    /**
     * Checks if a file exists on storage.
     */
    boolean exists(String storedName);

    /**
     * Lists all filenames currently residing in the storage directory (used for orphan cleanup).
     */
    List<String> listAllStoredFiles();
}

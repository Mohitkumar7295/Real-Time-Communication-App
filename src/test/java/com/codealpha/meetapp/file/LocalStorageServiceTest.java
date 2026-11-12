package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.file.storage.LocalStorageService;
import com.codealpha.meetapp.file.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalStorageService storageService;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setUploadDir(tempDir.toString());
        storageService = new LocalStorageService(appProperties);
        storageService.init();
    }

    @Test
    @DisplayName("Should atomically store and load bytes")
    void shouldStoreAndLoadBytes() {
        byte[] data = "Hello, MeetApp secure storage!".getBytes(StandardCharsets.UTF_8);
        String filename = "test-file-123.bin";

        storageService.store(data, filename);
        assertTrue(storageService.exists(filename));

        byte[] loaded = storageService.loadAsBytes(filename);
        assertArrayEquals(data, loaded);
    }

    @Test
    @DisplayName("Should successfully delete stored file")
    void shouldDeleteStoredFile() {
        byte[] data = "delete-me".getBytes(StandardCharsets.UTF_8);
        String filename = "delete-target.bin";

        storageService.store(data, filename);
        assertTrue(storageService.exists(filename));

        boolean deleted = storageService.delete(filename);
        assertTrue(deleted);
        assertFalse(storageService.exists(filename));
    }

    @Test
    @DisplayName("Should reject path traversal attempts in store and load")
    void shouldRejectPathTraversal() {
        byte[] data = "dangerous".getBytes(StandardCharsets.UTF_8);

        assertThrows(StorageException.class, () -> storageService.store(data, "../escape.bin"));
        assertThrows(StorageException.class, () -> storageService.store(data, "sub/dir/escape.bin"));
        assertThrows(StorageException.class, () -> storageService.store(data, "..\\escape.bin"));
        assertThrows(StorageException.class, () -> storageService.loadAsBytes("../escape.bin"));
    }

    @Test
    @DisplayName("Should list all stored files ignoring temp files")
    void shouldListStoredFilesIgnoringTmp() throws IOException {
        storageService.store("a".getBytes(StandardCharsets.UTF_8), "fileA.dat");
        storageService.store("b".getBytes(StandardCharsets.UTF_8), "fileB.dat");

        // Manually place a .tmp file
        Files.writeString(tempDir.resolve("upload-dummy.tmp"), "temp");

        List<String> list = storageService.listAllStoredFiles();
        assertEquals(2, list.size());
        assertTrue(list.contains("fileA.dat"));
        assertTrue(list.contains("fileB.dat"));
        assertFalse(list.contains("upload-dummy.tmp"));
    }
}

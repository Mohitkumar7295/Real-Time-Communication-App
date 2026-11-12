package com.codealpha.meetapp.file.storage;

import com.codealpha.meetapp.common.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Local filesystem implementation of {@link StorageService}.
 * Enforces path traversal checks, atomic writes via temp files,
 * and zero logging of file contents or byte buffers.
 */
@Service
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path rootDir;

    public LocalStorageService(AppProperties appProperties) {
        this.rootDir = Paths.get(appProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootDir);
            log.info("LocalStorageService initialized with root directory: {}", rootDir);
        } catch (IOException e) {
            throw new StorageException("Failed to initialize upload root directory", e);
        }
    }

    @Override
    public void store(byte[] bytes, String storedName) {
        validateStoredName(storedName);
        Path target = resolveAndVerify(storedName);

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(rootDir, "upload-", ".tmp");
            Files.write(tempFile, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
            throw new StorageException("Failed to store file: " + storedName, e);
        }
    }

    @Override
    public byte[] loadAsBytes(String storedName) {
        validateStoredName(storedName);
        Path target = resolveAndVerify(storedName);
        if (!Files.exists(target)) {
            throw new StorageException("File does not exist: " + storedName);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new StorageException("Failed to read file: " + storedName, e);
        }
    }

    @Override
    public boolean delete(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return false;
        }
        try {
            Path target = resolveAndVerify(storedName);
            return Files.deleteIfExists(target);
        } catch (Exception e) {
            log.warn("Error deleting file {}: {}", storedName, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return false;
        }
        try {
            Path target = resolveAndVerify(storedName);
            return Files.exists(target);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> listAllStoredFiles() {
        List<String> files = new ArrayList<>();
        if (!Files.exists(rootDir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(rootDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                  .forEach(path -> files.add(path.getFileName().toString()));
        } catch (IOException e) {
            log.error("Failed to list files in storage directory", e);
        }
        return files;
    }

    private void validateStoredName(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            throw new StorageException("Stored name must not be blank");
        }
        if (storedName.contains("..") || storedName.contains("/") || storedName.contains("\\")) {
            throw new StorageException("Path traversal characters not allowed in stored name: " + storedName);
        }
    }

    private Path resolveAndVerify(String storedName) {
        Path target = rootDir.resolve(storedName).normalize();
        if (!target.startsWith(rootDir)) {
            throw new StorageException("Path traversal attempt detected: " + storedName);
        }
        return target;
    }
}

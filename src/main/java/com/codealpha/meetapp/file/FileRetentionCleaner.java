package com.codealpha.meetapp.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled worker for running nightly retention cleanup of expired room files
 * and removing unreferenced orphaned files on disk.
 */
@Component
public class FileRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(FileRetentionCleaner.class);

    private final FileService fileService;

    public FileRetentionCleaner(FileService fileService) {
        this.fileService = fileService;
    }

    @Scheduled(cron = "${app.file-cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredFiles() {
        try {
            log.info("Starting scheduled file retention and orphan cleanup...");
            fileService.pruneRetentionFiles();
        } catch (Exception e) {
            log.error("Error during scheduled file retention cleanup", e);
        }
    }
}

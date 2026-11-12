package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Bounds concurrent heavy file I/O and encryption operations across the server,
 * shedding excess load with HTTP 429 and Retry-After: 5.
 */
@Component
public class FileOperationLimiter {

    private final Semaphore semaphore;

    public FileOperationLimiter(AppProperties appProperties) {
        int maxPermits = Math.max(1, appProperties.getMaxConcurrentFileOps());
        this.semaphore = new Semaphore(maxPermits, true);
    }

    public <T> T execute(Supplier<T> operation) {
        if (!semaphore.tryAcquire()) {
            throw new TooManyRequestsException("Server is busy processing file transfers. Please retry shortly.", 5);
        }
        try {
            return operation.get();
        } finally {
            semaphore.release();
        }
    }

    public void execute(Runnable operation) {
        execute(() -> {
            operation.run();
            return null;
        });
    }
}

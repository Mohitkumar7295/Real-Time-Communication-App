package com.codealpha.meetapp.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe in-memory rate limiter using sliding time windows.
 * Enforces caps on map size and regularly prunes expired entries to prevent memory exhaustion.
 */
@Component
public class RateLimiter {

    private static final int MAX_MAP_ENTRIES = 10_000;

    private static class WindowBucket {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long resetEpochMillis;

        WindowBucket(long resetEpochMillis) {
            this.resetEpochMillis = resetEpochMillis;
        }
    }

    private final ConcurrentHashMap<String, WindowBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Checks if an action is allowed for the given key and increments the counter.
     *
     * @param key           rate limit bucket key (e.g., "ip:127.0.0.1" or "email:test@example.com")
     * @param maxAttempts   maximum allowed attempts within the window
     * @param windowSeconds duration of the window in seconds
     * @return true if allowed, false if limit exceeded
     */
    public boolean tryAcquire(String key, int maxAttempts, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;

        if (buckets.size() > MAX_MAP_ENTRIES) {
            cleanUpExpired(now);
        }

        WindowBucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || now > existing.resetEpochMillis) {
                WindowBucket newBucket = new WindowBucket(now + windowMillis);
                newBucket.count.set(1);
                return newBucket;
            }
            existing.count.incrementAndGet();
            return existing;
        });

        return bucket.count.get() <= maxAttempts;
    }

    /**
     * Resets / clears a key after successful authentication.
     */
    public void reset(String key) {
        buckets.remove(key);
    }

    /**
     * Removes expired entries from the map.
     */
    public void cleanUpExpired(long now) {
        buckets.entrySet().removeIf(entry -> now > entry.getValue().resetEpochMillis);
    }
}

package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FileOperationLimiterTest {

    @Test
    @DisplayName("Should allow operations up to configured concurrency limit and shed excess with 429")
    void shouldShedExcessLoad() throws InterruptedException {
        AppProperties properties = new AppProperties();
        properties.setMaxConcurrentFileOps(2);

        FileOperationLimiter limiter = new FileOperationLimiter(properties);

        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(2);
        AtomicBoolean thread1Active = new AtomicBoolean(false);
        AtomicBoolean thread2Active = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            limiter.execute(() -> {
                thread1Active.set(true);
                startedLatch.countDown();
                try {
                    holdLatch.await();
                } catch (InterruptedException ignored) {}
                return null;
            });
        });

        Thread t2 = new Thread(() -> {
            limiter.execute(() -> {
                thread2Active.set(true);
                startedLatch.countDown();
                try {
                    holdLatch.await();
                } catch (InterruptedException ignored) {}
                return null;
            });
        });

        t1.start();
        t2.start();

        startedLatch.await();

        // Now 2 permits are held. A 3rd invocation must immediately throw TooManyRequestsException
        TooManyRequestsException ex = assertThrows(TooManyRequestsException.class, () ->
                limiter.execute(() -> "should fail")
        );
        assertEquals(5, ex.getRetryAfterSeconds());

        // Release latch
        holdLatch.countDown();
        t1.join();
        t2.join();

        // Now a subsequent invocation should succeed
        String result = limiter.execute(() -> "success");
        assertEquals("success", result);
    }
}

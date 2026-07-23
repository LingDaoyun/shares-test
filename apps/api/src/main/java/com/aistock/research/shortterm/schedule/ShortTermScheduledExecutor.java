package com.aistock.research.shortterm.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ShortTermScheduledExecutor extends ThreadPoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShortTermScheduledExecutor.class);
    private static final Duration FORCED_SHUTDOWN_WAIT = Duration.ofSeconds(5);

    private final Duration gracefulShutdownTimeout;

    ShortTermScheduledExecutor(ThreadFactory threadFactory, Duration gracefulShutdownTimeout) {
        super(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                threadFactory,
                new AbortPolicy()
        );
        if (gracefulShutdownTimeout == null
                || gracefulShutdownTimeout.isZero()
                || gracefulShutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("gracefulShutdownTimeout must be positive");
        }
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
    }

    public void shutdownGracefully() {
        shutdown();
        try {
            if (awaitTermination(gracefulShutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
            log.warn(
                    "Scheduled short-term executor did not stop within {}, interrupting remaining tasks",
                    gracefulShutdownTimeout);
            shutdownNow();
            awaitTermination(FORCED_SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

package com.aistock.research.shortterm.schedule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ShortTermScheduledExecutorConfig {

    private final AtomicInteger threadSequence = new AtomicInteger();

    @Bean(name = "shortTermScheduledExecutor", destroyMethod = "shutdown")
    ExecutorService shortTermScheduledExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                namedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private ThreadFactory namedThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("short-term-scheduled-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

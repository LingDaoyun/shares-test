package com.aistock.research.shortterm.schedule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ShortTermScheduledExecutorConfig {

    private final AtomicInteger threadSequence = new AtomicInteger();

    @Bean(name = "shortTermScheduledExecutor", destroyMethod = "shutdownGracefully")
    ShortTermScheduledExecutor shortTermScheduledExecutor() {
        return new ShortTermScheduledExecutor(namedThreadFactory(), Duration.ofSeconds(30));
    }

    private ThreadFactory namedThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("short-term-scheduled-" + threadSequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}

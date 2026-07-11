package com.aistock.research.shortterm;

import java.time.Instant;

public record ShortTermScanJobStatus(
        String jobId,
        String status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String message,
        ShortTermReport report
) {
}

package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;

public record ShortTermCoverageSnapshot(
        int expectedCount,
        int fetchedCount,
        int missingCount,
        BigDecimal coverageRatio,
        boolean executionReliable,
        String source,
        Instant fetchedAt
) {
    public static ShortTermCoverageSnapshot unreliable() {
        return new ShortTermCoverageSnapshot(
                0,
                0,
                0,
                BigDecimal.ZERO,
                false,
                "未知",
                null
        );
    }
}

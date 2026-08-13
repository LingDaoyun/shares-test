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
        Instant fetchedAt,
        int rawExpectedCount,
        int rawFetchedCount,
        int excludedNoPriceCount,
        boolean rawComplete
) {
    public ShortTermCoverageSnapshot(
            int expectedCount,
            int fetchedCount,
            int missingCount,
            BigDecimal coverageRatio,
            boolean executionReliable,
            String source,
            Instant fetchedAt
    ) {
        this(
                expectedCount,
                fetchedCount,
                missingCount,
                coverageRatio,
                executionReliable,
                source,
                fetchedAt,
                expectedCount,
                executionReliable ? expectedCount : fetchedCount,
                0,
                executionReliable
        );
    }

    public static ShortTermCoverageSnapshot unreliable() {
        return new ShortTermCoverageSnapshot(
                0,
                0,
                0,
                BigDecimal.ZERO,
                false,
                "未知",
                null,
                0,
                0,
                0,
                false
        );
    }
}

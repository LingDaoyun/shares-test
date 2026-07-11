package com.aistock.research.universe;

import java.time.Instant;

public record UniversalScreenCoverage(
        int requestedCount,
        int expectedCount,
        int fetchedCount,
        int missingCount,
        boolean complete,
        String source,
        Instant fetchedAt
) {
}

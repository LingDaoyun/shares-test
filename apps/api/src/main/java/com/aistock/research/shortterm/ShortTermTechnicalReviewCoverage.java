package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermTechnicalReviewCoverage(
        int quotePreselectedCount,
        int requestedCount,
        int sufficientCount,
        int missingCount,
        BigDecimal coverageRatio
) {
    public static ShortTermTechnicalReviewCoverage of(
            int quotePreselectedCount,
            int requestedCount,
            int sufficientCount
    ) {
        int denominator = Math.max(0, quotePreselectedCount);
        int safeRequested = Math.max(0, requestedCount);
        int safeSufficient = Math.max(0, Math.min(sufficientCount, safeRequested));
        BigDecimal ratio = denominator == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(safeSufficient)
                .divide(BigDecimal.valueOf(denominator), 4, java.math.RoundingMode.HALF_UP);
        return new ShortTermTechnicalReviewCoverage(
                denominator,
                safeRequested,
                safeSufficient,
                Math.max(0, denominator - safeSufficient),
                ratio
        );
    }

    public static ShortTermTechnicalReviewCoverage unavailable() {
        return of(0, 0, 0);
    }
}

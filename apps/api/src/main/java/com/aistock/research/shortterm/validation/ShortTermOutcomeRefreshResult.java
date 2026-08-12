package com.aistock.research.shortterm.validation;

public record ShortTermOutcomeRefreshResult(
        int observationCount,
        int maturedCount,
        int unavailableCount,
        int retryableCount,
        int pendingCount
) {
    public static ShortTermOutcomeRefreshResult empty() {
        return new ShortTermOutcomeRefreshResult(0, 0, 0, 0, 0);
    }
}

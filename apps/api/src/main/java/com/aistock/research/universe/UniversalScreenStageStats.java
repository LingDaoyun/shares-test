package com.aistock.research.universe;

public record UniversalScreenStageStats(
        String stage,
        String label,
        int inputCount,
        int passedCount,
        int excludedCount,
        int deferredCount
) {
}

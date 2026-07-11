package com.aistock.research.ai;

import java.time.Instant;
import java.time.LocalDate;

public record TrendAnalysisHistoryItem(
        Long recordId,
        LocalDate analysisDate,
        String documentTitle,
        String sourceOrganization,
        String publishedAt,
        String sourceUrl,
        String promptVersion,
        String provider,
        String model,
        String overallSummary,
        String overallConfidence,
        String nextAction,
        Instant analyzedAt
) {
}

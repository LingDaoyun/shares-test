package com.aistock.research.history;

import java.math.BigDecimal;
import java.time.Instant;

public record DecisionHistoryEntry(
        String decisionId,
        String analysisId,
        String symbol,
        String sourceType,
        String actionStage,
        String actionLabel,
        BigDecimal decisionScore,
        String ruleVersion,
        Instant dataAsOf,
        Instant recordedAt
) {
}

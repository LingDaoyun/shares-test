package com.aistock.research.dailysignal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DailySignalReport(
        String scope,
        String sourceProject,
        String sourceCommit,
        DailyMarketContext marketContext,
        Map<String, Long> actionCounts,
        List<StrategyPlaybook> strategyPlaybooks,
        List<DailyDecisionSignal> signals,
        Map<String, String> tradeCaptureTokens,
        Instant generatedAt
) {
    public DailySignalReport(
            String scope,
            String sourceProject,
            String sourceCommit,
            DailyMarketContext marketContext,
            Map<String, Long> actionCounts,
            List<StrategyPlaybook> strategyPlaybooks,
            List<DailyDecisionSignal> signals,
            Instant generatedAt
    ) {
        this(scope, sourceProject, sourceCommit, marketContext, actionCounts, strategyPlaybooks, signals,
                Map.of(), generatedAt);
    }
}

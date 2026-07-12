package com.aistock.research.tech;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TechTrackingReport(
        String scope,
        int universeCount,
        int candidateCount,
        String quoteNote,
        List<String> methodology,
        List<TechEvidenceItem> policySignals,
        TechTrackingRuleSet ruleSet,
        List<TechTrackedStock> candidates,
        Map<String, String> tradeCaptureTokens,
        Instant generatedAt
) {
    public TechTrackingReport(
            String scope,
            int universeCount,
            int candidateCount,
            String quoteNote,
            List<String> methodology,
            List<TechEvidenceItem> policySignals,
            TechTrackingRuleSet ruleSet,
            List<TechTrackedStock> candidates,
            Instant generatedAt
    ) {
        this(scope, universeCount, candidateCount, quoteNote, methodology, policySignals, ruleSet, candidates,
                Map.of(), generatedAt);
    }
}

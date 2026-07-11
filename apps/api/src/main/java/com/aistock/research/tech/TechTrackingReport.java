package com.aistock.research.tech;

import java.time.Instant;
import java.util.List;

public record TechTrackingReport(
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
}

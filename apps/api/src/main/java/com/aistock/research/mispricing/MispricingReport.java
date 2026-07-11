package com.aistock.research.mispricing;

import java.time.Instant;
import java.util.List;

public record MispricingReport(
        String scope,
        int universeCount,
        int candidateCount,
        String quoteNote,
        List<String> methodology,
        StyleHeatSnapshot styleHeat,
        MispricingRuleSet ruleSet,
        List<MispricingEvidenceItem> policySignals,
        List<MispricedAsset> candidates,
        Instant generatedAt
) {
}

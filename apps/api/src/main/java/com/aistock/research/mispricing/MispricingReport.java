package com.aistock.research.mispricing;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Map<String, String> tradeCaptureTokens,
        Instant generatedAt
) {
    public MispricingReport(
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
        this(scope, universeCount, candidateCount, quoteNote, methodology, styleHeat, ruleSet, policySignals,
                candidates, Map.of(), generatedAt);
    }
}

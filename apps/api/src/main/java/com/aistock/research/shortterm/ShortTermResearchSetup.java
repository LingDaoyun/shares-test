package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

/** Research ranking evidence, not a return forecast or an execution authorization. */
public record ShortTermResearchSetup(
        String ruleVersion,
        String setupType,
        String setupLabel,
        String state,
        BigDecimal score,
        BigDecimal trendScore,
        BigDecimal relativeStrengthScore,
        BigDecimal setupScore,
        BigDecimal volumePriceScore,
        BigDecimal trendContribution,
        BigDecimal relativeStrengthContribution,
        BigDecimal setupContribution,
        BigDecimal volumePriceContribution,
        BigDecimal triggerPrice,
        BigDecimal invalidationPrice,
        List<String> evidence,
        List<String> risks,
        List<String> dataGaps,
        boolean executionEvidenceReady
) {
    public ShortTermResearchSetup {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        risks = risks == null ? List.of() : List.copyOf(risks);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public boolean confirmed() { return "CONFIRMED".equals(state); }

    /** Identifies a structural alternative to a golden cross; WATCH remains non-confirmed. */
    public boolean matched() {
        return "CONTRACTION_BREAKOUT".equals(setupType)
                || "TREND_PULLBACK".equals(setupType)
                || "SUPPORT_REVERSAL".equals(setupType);
    }
}

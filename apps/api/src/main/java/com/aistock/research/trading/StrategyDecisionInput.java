package com.aistock.research.trading;

import java.math.BigDecimal;
import java.util.List;

public record StrategyDecisionInput(
        String sourceCode,
        String sourceLabel,
        String strategyType,
        String action,
        String actionLabel,
        int confidence,
        BigDecimal score,
        String reason,
        List<String> riskControls,
        String sourceModule,
        String ruleVersion
) {
    public StrategyDecisionInput(
            String sourceCode,
            String sourceLabel,
            String strategyType,
            String action,
            String actionLabel,
            int confidence,
            BigDecimal score,
            String reason,
            List<String> riskControls
    ) {
        this(sourceCode, sourceLabel, strategyType, action, actionLabel, confidence, score, reason, riskControls, null, null);
    }

    public StrategyDecisionInput {
        riskControls = riskControls == null ? List.of() : List.copyOf(riskControls);
    }
}

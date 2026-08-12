package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record MaturedRecommendationRow(
        String caseId,
        String sourceModule,
        String ruleVersion,
        String horizon,
        BigDecimal recommendedPrice,
        Instant recommendedAt,
        BigDecimal returnPct,
        BigDecimal maxRunupPct,
        BigDecimal maxDrawdownPct
) {
    public MaturedRecommendationRow(
            String caseId,
            String sourceModule,
            String ruleVersion,
            BigDecimal recommendedPrice,
            Instant recommendedAt,
            BigDecimal returnPct,
            BigDecimal maxRunupPct,
            BigDecimal maxDrawdownPct
    ) {
        this(caseId, sourceModule, ruleVersion, "T20", recommendedPrice, recommendedAt,
                returnPct, maxRunupPct, maxDrawdownPct);
    }
}

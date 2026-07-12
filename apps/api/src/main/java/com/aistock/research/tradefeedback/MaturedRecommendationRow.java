package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record MaturedRecommendationRow(
        String caseId,
        String sourceModule,
        String ruleVersion,
        BigDecimal recommendedPrice,
        Instant recommendedAt,
        BigDecimal returnPct,
        BigDecimal maxRunupPct,
        BigDecimal maxDrawdownPct
) {
}

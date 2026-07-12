package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record VerifiedRecommendationSnapshot(
        String attestationId,
        String symbol,
        String companyName,
        String sourceModule,
        String recommendationAction,
        BigDecimal recommendationScore,
        String ruleVersion,
        BigDecimal recommendedPrice,
        Instant recommendedAt,
        String recommendationPayloadJson
) {
}

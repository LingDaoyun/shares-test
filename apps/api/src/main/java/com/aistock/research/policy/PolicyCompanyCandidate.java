package com.aistock.research.policy;

import java.math.BigDecimal;
import java.util.List;

public record PolicyCompanyCandidate(
        String symbol,
        String companyName,
        String industry,
        String chainSegment,
        String researchRole,
        List<String> leadershipRationale,
        BigDecimal financialQualityScore,
        String financialQualityLabel,
        BigDecimal latestPrice,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal amount,
        String actionLabel,
        List<String> dataGaps
) {
}

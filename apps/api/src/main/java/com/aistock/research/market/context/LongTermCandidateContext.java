package com.aistock.research.market.context;

import java.time.Instant;
import java.util.List;

public record LongTermCandidateContext(
        String symbol,
        String companyName,
        String market,
        String industry,
        LongTermIndustryContext industryContext,
        LongTermPolicyEvidence policyEvidence,
        LongTermCycleSnapshot cycleContext,
        Instant generatedAt,
        List<String> dataGaps
) {
}

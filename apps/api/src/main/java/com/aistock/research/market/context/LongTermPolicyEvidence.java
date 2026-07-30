package com.aistock.research.market.context;

import java.util.List;

public record LongTermPolicyEvidence(
        List<LongTermPolicyDocument> documents,
        List<String> dataGaps
) {
}

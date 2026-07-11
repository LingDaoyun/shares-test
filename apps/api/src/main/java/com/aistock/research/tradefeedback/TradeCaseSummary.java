package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeCaseSummary(
        String caseId,
        String symbol,
        String companyName,
        String sourceModule,
        String recommendationAction,
        BigDecimal recommendationScore,
        String ruleVersion,
        BigDecimal recommendedPrice,
        Instant recommendedAt,
        String status,
        TradeLedgerSummary ledger,
        Instant createdAt,
        Instant updatedAt
) {
}

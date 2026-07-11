package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TradeCaseDetail(
        String caseId,
        String decisionId,
        String symbol,
        String companyName,
        String sourceModule,
        String recommendationAction,
        BigDecimal recommendationScore,
        String ruleVersion,
        BigDecimal recommendedPrice,
        Instant recommendedAt,
        Object recommendationPayload,
        String status,
        TradeLedgerSummary ledger,
        List<TradeFillView> fills,
        Instant createdAt,
        Instant updatedAt
) {
}

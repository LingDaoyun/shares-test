package com.aistock.research.decision;

import java.math.BigDecimal;
import java.util.List;

public record InvestmentDecisionGate(
        String gateCode,
        String gateName,
        String status,
        String statusLabel,
        BigDecimal scoreImpact,
        String conclusion,
        List<String> evidenceRefs
) {
}

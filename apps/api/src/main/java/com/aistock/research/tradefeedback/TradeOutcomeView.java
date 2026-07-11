package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TradeOutcomeView(
        String baselineType,
        String horizon,
        BigDecimal baselinePrice,
        BigDecimal evaluationPrice,
        LocalDate evaluationDate,
        BigDecimal returnPct,
        BigDecimal maxRunupPct,
        BigDecimal maxDrawdownPct,
        String status,
        Instant calculatedAt
) {
}

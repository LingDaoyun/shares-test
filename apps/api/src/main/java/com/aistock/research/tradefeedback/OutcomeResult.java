package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OutcomeResult(
        String horizon,
        BigDecimal baselinePrice,
        BigDecimal evaluationPrice,
        LocalDate evaluationDate,
        BigDecimal returnPct,
        BigDecimal maxRunupPct,
        BigDecimal maxDrawdownPct,
        String status
) {

    public static OutcomeResult pending(String horizon) {
        return new OutcomeResult(horizon, null, null, null, null, null, null, "PENDING");
    }

    public static OutcomeResult unavailable(String horizon) {
        return new OutcomeResult(horizon, null, null, null, null, null, null, "UNAVAILABLE");
    }
}

package com.aistock.research.shortterm.validation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ShortTermHorizonEvaluation(
        String horizon,
        String status,
        LocalDate evaluationDate,
        BigDecimal evaluationPrice,
        BigDecimal grossReturnPercent,
        BigDecimal netReturnPercent,
        BigDecimal maxFavorableExcursionPercent,
        BigDecimal maxAdverseExcursionPercent,
        String detail
) {
    public static ShortTermHorizonEvaluation unavailable(
            String horizon,
            String status,
            LocalDate targetDate,
            String detail
    ) {
        return new ShortTermHorizonEvaluation(
                horizon, status, targetDate, null, null, null, null, null, detail);
    }
}

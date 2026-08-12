package com.aistock.research.shortterm.validation;

import java.math.BigDecimal;

public record ShortTermValidationSample(
        BigDecimal netReturnPercent,
        BigDecimal maxFavorableExcursionPercent,
        BigDecimal maxAdverseExcursionPercent
) {
}

package com.aistock.research.rule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.Map;

public record FactorSnapshot(
        @NotBlank String symbol,
        @NotEmpty Map<String, BigDecimal> factors
) {
}


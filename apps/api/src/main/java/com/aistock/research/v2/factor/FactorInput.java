package com.aistock.research.v2.factor;

import java.math.BigDecimal;
import java.util.Map;

public record FactorInput(String symbol, Map<String, Measure> measures) {

    public FactorInput {
        measures = measures == null ? Map.of() : Map.copyOf(measures);
    }

    public record Measure(BigDecimal value, String unit) {
    }
}

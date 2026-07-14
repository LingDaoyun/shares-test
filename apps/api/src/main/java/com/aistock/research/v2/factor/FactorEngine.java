package com.aistock.research.v2.factor;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class FactorEngine {

    public FactorValue evaluate(FactorDefinition definition, FactorInput input) {
        FactorInput.Measure measure = input.measures().get(definition.requiredField());
        if (measure == null || measure.value() == null) {
            if (definition.missingPolicy() == FactorMissingPolicy.BLOCK) {
                return new FactorValue(definition.code(), input.symbol(), null, null,
                        new BigDecimal("-100.00"),
                        "MISSING_REQUIRED_FIELD:" + definition.requiredField());
            }
            return new FactorValue(definition.code(), input.symbol(), null, null,
                    new BigDecimal("-15.00"),
                    "MISSING_REQUIRED_FIELD:" + definition.requiredField());
        }
        if (!definition.valueUnit().equals(measure.unit())) {
            throw new IllegalArgumentException("UNIT_MISMATCH:" + definition.code()
                    + " expected " + definition.valueUnit() + " but got " + measure.unit());
        }
        BigDecimal normalized = normalizeToPercentileLikeScore(measure.value(), definition.direction());
        return new FactorValue(definition.code(), input.symbol(), measure.value(), normalized,
                new BigDecimal("0.00"), "");
    }

    private BigDecimal normalizeToPercentileLikeScore(BigDecimal value, FactorDirection direction) {
        BigDecimal bounded = value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal score = bounded.multiply(new BigDecimal("100"));
        if (direction == FactorDirection.LOWER_IS_BETTER) {
            score = new BigDecimal("100").subtract(score);
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }
}

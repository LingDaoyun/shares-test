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
        if (!definition.hasNormalizedScoringUnit()) {
            throw new IllegalArgumentException("UNSUPPORTED_SCORING_UNIT:" + definition.code()
                    + " " + definition.valueUnit());
        }
        BigDecimal normalized = normalizeToPercentileLikeScore(measure.value(), definition.valueUnit(), definition.direction(),
                definition.code());
        return new FactorValue(definition.code(), input.symbol(), measure.value(), normalized,
                new BigDecimal("0.00"), "");
    }

    private BigDecimal normalizeToPercentileLikeScore(
            BigDecimal value,
            String unit,
            FactorDirection direction,
            String code
    ) {
        BigDecimal upperBound = "ratio".equals(unit) ? BigDecimal.ONE : new BigDecimal("100");
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(upperBound) > 0) {
            throw new IllegalArgumentException("NORMALIZED_VALUE_OUT_OF_RANGE:" + code + " " + unit);
        }
        BigDecimal score = "ratio".equals(unit) ? value.multiply(new BigDecimal("100")) : value;
        if (direction == FactorDirection.LOWER_IS_BETTER) {
            score = new BigDecimal("100").subtract(score);
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }
}

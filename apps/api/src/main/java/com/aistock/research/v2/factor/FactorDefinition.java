package com.aistock.research.v2.factor;

public record FactorDefinition(
        String code,
        String name,
        String strategyScope,
        String valueUnit,
        FactorDirection direction,
        String requiredField,
        FactorMissingPolicy missingPolicy,
        String version
) {
}

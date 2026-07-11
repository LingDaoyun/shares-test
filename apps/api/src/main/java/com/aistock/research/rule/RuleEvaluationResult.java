package com.aistock.research.rule;

import java.math.BigDecimal;
import java.util.List;

public record RuleEvaluationResult(
        String ruleCode,
        String name,
        int ruleVersion,
        RuleAction action,
        boolean passed,
        BigDecimal score,
        List<ConditionEvaluation> conditions
) {
}


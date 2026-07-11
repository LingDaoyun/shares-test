package com.aistock.research.rule;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RuleEngineService {

    public RuleEvaluationResult evaluate(RuleDefinition rule, FactorSnapshot snapshot) {
        if (!rule.enabled()) {
            return new RuleEvaluationResult(
                    rule.ruleCode(),
                    rule.name(),
                    rule.version(),
                    RuleAction.REVIEW,
                    true,
                    BigDecimal.ZERO,
                    List.of()
            );
        }

        Map<String, BigDecimal> factors = snapshot.factors();
        List<ConditionEvaluation> evaluations = new ArrayList<>();
        BigDecimal passedWeight = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;

        for (RuleCondition condition : rule.conditions()) {
            BigDecimal weight = condition.effectiveWeight();
            BigDecimal actual = factors.get(condition.factor());
            boolean passed = actual != null && condition.operator().test(actual, condition.value());
            totalWeight = totalWeight.add(weight);
            if (passed) {
                passedWeight = passedWeight.add(weight);
            }
            evaluations.add(new ConditionEvaluation(
                    condition.factor(),
                    condition.operator(),
                    condition.value(),
                    actual,
                    passed,
                    buildMessage(condition, actual, passed)
            ));
        }

        boolean allPassed = evaluations.stream().allMatch(ConditionEvaluation::passed);
        BigDecimal score = totalWeight.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : passedWeight.multiply(BigDecimal.valueOf(100)).divide(totalWeight, 2, RoundingMode.HALF_UP);
        return new RuleEvaluationResult(rule.ruleCode(), rule.name(), rule.version(), rule.action(), allPassed, score, evaluations);
    }

    private String buildMessage(RuleCondition condition, BigDecimal actual, boolean passed) {
        if (actual == null) {
            return "缺少因子：" + condition.factor();
        }
        String status = passed ? "通过" : "未通过";
        return status + "，实际值 " + actual + " " + condition.operator().symbol() + " 阈值 " + condition.value();
    }
}


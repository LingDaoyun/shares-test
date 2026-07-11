package com.aistock.research.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineServiceTest {

    private final RuleEngineService engineService = new RuleEngineService();

    @Test
    void shouldPassWhenAllConditionsMatch() {
        RuleDefinition rule = new RuleDefinition(
                "QUALITY_FILTER",
                "财务质量过滤",
                true,
                1,
                List.of(
                        new RuleCondition("roe_5y_median", Operator.GTE, new BigDecimal("0.08"), BigDecimal.ONE),
                        new RuleCondition("debt_to_asset", Operator.LTE, new BigDecimal("0.65"), BigDecimal.ONE)
                ),
                RuleAction.PASS,
                "test",
                Instant.now()
        );
        FactorSnapshot snapshot = new FactorSnapshot("DEMO001", Map.of(
                "roe_5y_median", new BigDecimal("0.12"),
                "debt_to_asset", new BigDecimal("0.40")
        ));

        RuleEvaluationResult result = engineService.evaluate(rule, snapshot);

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualByComparingTo("100.00");
    }

    @Test
    void shouldFailMissingFactorAndKeepPartialScore() {
        RuleDefinition rule = new RuleDefinition(
                "RISK_BLOCKLIST",
                "重大风险排雷",
                true,
                1,
                List.of(
                        new RuleCondition("pledge_ratio", Operator.LTE, new BigDecimal("0.35"), BigDecimal.ONE),
                        new RuleCondition("goodwill_to_equity", Operator.LTE, new BigDecimal("0.20"), BigDecimal.ONE)
                ),
                RuleAction.REJECT,
                "test",
                Instant.now()
        );
        FactorSnapshot snapshot = new FactorSnapshot("DEMO001", Map.of(
                "pledge_ratio", new BigDecimal("0.10")
        ));

        RuleEvaluationResult result = engineService.evaluate(rule, snapshot);

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualByComparingTo("50.00");
        assertThat(result.conditions()).anyMatch(condition -> condition.actual() == null);
    }
}


package com.aistock.research.v2.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyValidationGateTest {

    private final StrategyValidationGate gate = new StrategyValidationGate();

    @Test
    void downgradesBuyActionWhenOutOfSampleValidationIsInsufficient() {
        StrategySignal raw = rawAddSignal();
        StrategyValidationSummary summary = new StrategyValidationSummary(
                StrategyCode.SHORT_RIGHT_SIDE,
                "short-right-side-v2.0.0",
                18,
                new BigDecimal("61.00"),
                new BigDecimal("18.00"),
                "2025Q4-2026Q2"
        );

        StrategySignal gated = gate.apply(raw, summary);

        assertThat(gated.action()).isEqualTo(StrategyAction.NEXT_WATCH);
        assertThat(gated.positionLimit()).isZero();
        assertThat(gated.historicalHitRate()).isEqualByComparingTo("61.00");
        assertThat(gated.context()).containsEntry("validationStatus", "INSUFFICIENT_OOS");
        assertThat(gated.evidenceSummary()).anySatisfy(item ->
                assertThat(item).contains("样本外验证不足", "18"));
    }

    @Test
    void keepsBuyActionAndAddsValidationEvidenceWhenPublishedGatePasses() {
        StrategySignal raw = rawAddSignal();
        StrategyValidationSummary summary = new StrategyValidationSummary(
                StrategyCode.SHORT_RIGHT_SIDE,
                "short-right-side-v2.0.0",
                120,
                new BigDecimal("57.50"),
                new BigDecimal("16.20"),
                "2024Q1-2026Q2"
        );

        StrategySignal gated = gate.apply(raw, summary);

        assertThat(gated.action()).isEqualTo(StrategyAction.ADD);
        assertThat(gated.positionLimit()).isEqualByComparingTo("0.0800");
        assertThat(gated.historicalHitRate()).isEqualByComparingTo("57.50");
        assertThat(gated.context()).containsEntry("validationStatus", "PASSED_OOS");
        assertThat(gated.evidenceSummary()).anySatisfy(item ->
                assertThat(item).contains("样本外验证通过", "120"));
    }

    private StrategySignal rawAddSignal() {
        return new StrategySignal(
                StrategyCode.SHORT_RIGHT_SIDE,
                "short-right-side-v2.0.0",
                "000977",
                "浪潮信息",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T07:19:30Z"),
                CandidateStage.QUALIFIED,
                StrategyAction.ADD,
                new BigDecimal("0.0800"),
                "14:45-14:56 尾盘窗口内确认分批买入。",
                "跌破 20 日线退出。",
                new BigDecimal("78.00"),
                new BigDecimal("90.00"),
                null,
                new BigDecimal("2.20"),
                List.of("右侧结构与缩量承接同向。"),
                List.of(),
                Map.of("strategyFamily", "SHORT_TERM"),
                SourceQualityStatus.VERIFIED,
                Map.of("source", "unit-test"),
                SignalProvenance.RULE_ENGINE
        );
    }
}

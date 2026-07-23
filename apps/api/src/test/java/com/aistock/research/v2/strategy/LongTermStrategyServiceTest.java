package com.aistock.research.v2.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermStrategyServiceTest {

    private final LongTermStrategyService service = new LongTermStrategyService();

    @Test
    void evaluatesLongTermStrategyFamilyWithoutOnePePbGate() {
        LongTermStrategyInput input = new LongTermStrategyInput(
                "002714",
                "牧原股份",
                "生猪养殖",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T07:19:30Z"),
                new BigDecimal("56"),
                new BigDecimal("74"),
                new BigDecimal("83"),
                new BigDecimal("72"),
                new BigDecimal("68"),
                new BigDecimal("88"),
                new BigDecimal("62"),
                new BigDecimal("92"),
                new BigDecimal("70"),
                new BigDecimal("86"),
                List.of()
        );

        Map<StrategyCode, StrategySignal> signals = service.evaluate(input).stream()
                .collect(Collectors.toMap(StrategySignal::strategyCode, Function.identity()));

        assertThat(signals).containsOnlyKeys(
                StrategyCode.VALUE_REVERSION,
                StrategyCode.QUALITY_COMPOUNDER,
                StrategyCode.CYCLE_REVERSAL);
        assertThat(signals.get(StrategyCode.VALUE_REVERSION).action()).isEqualTo(StrategyAction.NEXT_WATCH);
        assertThat(signals.get(StrategyCode.CYCLE_REVERSAL).action()).isEqualTo(StrategyAction.LIGHT_TRIAL);
        assertThat(signals.get(StrategyCode.CYCLE_REVERSAL).positionLimit()).isEqualByComparingTo("0.0500");
        assertThat(signals.get(StrategyCode.CYCLE_REVERSAL).evidenceSummary())
                .anySatisfy(item -> assertThat(item).contains("周期位置"));
        assertThat(signals.get(StrategyCode.CYCLE_REVERSAL).context())
                .containsEntry("strategyFamily", "LONG_TERM")
                .containsEntry("valuationUsage", "CONTEXT_NOT_HARD_GATE");
    }

    @Test
    void keepsQualityCompounderResearchableWhenValuationIsExpensiveButBusinessQualityIsStrong() {
        LongTermStrategyInput input = baseQualityInput(new BigDecimal("24"));

        StrategySignal quality = service.evaluate(input).stream()
                .filter(signal -> signal.strategyCode() == StrategyCode.QUALITY_COMPOUNDER)
                .findFirst()
                .orElseThrow();

        assertThat(quality.action()).isIn(StrategyAction.NEXT_WATCH, StrategyAction.LIGHT_TRIAL);
        assertThat(quality.candidateStage()).isNotEqualTo(CandidateStage.BLOCKED);
        assertThat(quality.blockedReasons()).isEmpty();
        assertThat(quality.context()).containsEntry("valuationUsage", "CONTEXT_NOT_HARD_GATE");
        assertThat(quality.evidenceSummary())
                .anySatisfy(item -> assertThat(item).contains("质量", "壁垒"));
    }

    @Test
    void blocksAllLongTermStrategiesWhenHardRiskFlagExists() {
        LongTermStrategyInput input = new LongTermStrategyInput(
                "000001",
                "测试公司",
                "测试行业",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T07:19:30Z"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                new BigDecimal("82"),
                List.of("ST", "重大财务疑点")
        );

        assertThat(service.evaluate(input))
                .hasSize(3)
                .allSatisfy(signal -> {
                    assertThat(signal.candidateStage()).isEqualTo(CandidateStage.BLOCKED);
                    assertThat(signal.action()).isEqualTo(StrategyAction.RISK_BLOCKED);
                    assertThat(signal.blockedReasons()).contains("HARD_RISK_FLAG:ST");
                });
    }

    private LongTermStrategyInput baseQualityInput(BigDecimal valuationDiscountScore) {
        return new LongTermStrategyInput(
                "600519",
                "贵州茅台",
                "白酒",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T07:19:30Z"),
                valuationDiscountScore,
                new BigDecimal("94"),
                new BigDecimal("96"),
                new BigDecimal("92"),
                new BigDecimal("90"),
                new BigDecimal("35"),
                new BigDecimal("45"),
                new BigDecimal("95"),
                new BigDecimal("58"),
                new BigDecimal("88"),
                List.of()
        );
    }
}

package com.aistock.research.shortterm;

import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermTradePlanServiceTest {

    private final ShortTermTradePlanService service = new ShortTermTradePlanService(new TradingClockService());

    @Test
    void marketLightConstraintCapsActualPositionAndKeepsNormalPlanPrices() {
        ShortTermTradePlan original = service.create(LocalDate.of(2026, 7, 23), bd("10"), bd("9.9"), bd("10.02"),
                technicalWithAtrAndSupport("3", "9.72"), rules());
        ShortTermTradePlan capped = original.withMarketConstraint(market("REPAIR", "LIGHT_TRIAL"),
                Instant.parse("2026-07-23T06:49:00Z"), true);
        assertThat(capped.status()).isEqualTo("ACTIONABLE");
        assertThat(capped.maxPositionRatio()).isEqualByComparingTo("0.20");
        assertThat(capped.hardStopPrice()).isEqualTo(original.hardStopPrice());
        assertThat(capped.withMarketConstraint(market("TREND_EXPANSION", "NORMAL"),
                Instant.parse("2026-07-23T06:49:00Z"), true).maxPositionRatio()).isEqualByComparingTo("0.20");
    }

    @Test
    void unavailableMarketCannotBeOverriddenByActionableTailPlan() {
        ShortTermTradePlan original = service.create(LocalDate.of(2026, 7, 23), bd("10"), bd("9.9"), bd("10.02"),
                technicalWithAtrAndSupport("3", "9.72"), rules());
        ShortTermTradePlan blocked = original.withMarketConstraint(ShortTermMarketRegime.unavailable("行情不足"),
                Instant.parse("2026-07-23T06:49:00Z"), true);
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.maxPositionRatio()).isEqualByComparingTo("0");
        assertThat(blocked.blockedReasons()).anyMatch(reason -> reason.contains("大盘"));
    }

    @Test
    void earlierActionablePlanExpiresAtFinalOutputBoundary() {
        ShortTermTradePlan original = service.create(LocalDate.of(2026, 7, 23), bd("10"), bd("9.9"), bd("10.02"),
                technicalWithAtrAndSupport("3", "9.72"), rules());
        assertThat(original.withMarketConstraint(market("TREND_EXPANSION", "NORMAL"),
                Instant.parse("2026-07-23T06:50:00Z"), true).status()).isEqualTo("BLOCKED");
        assertThat(original.withMarketConstraint(market("TREND_EXPANSION", "NORMAL"),
                Instant.parse("2026-07-23T06:44:00Z"), false).status()).isEqualTo("BLOCKED");
    }

    private ShortTermMarketRegime market(String state, String action) {
        return new ShortTermMarketRegime(state, "测试", null, null, null, null, null, null,
                100, action, "测试市场约束", List.of());
    }

    @Test
    void createsClampedT1MorningPlanWithoutT2Extension() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.72"),
                rules()
        );

        assertThat(plan.strategyLabel()).isEqualTo("隔夜早盘退出");
        assertThat(plan.status()).isEqualTo("ACTIONABLE");
        assertThat(plan.firstTargetPercent()).isEqualByComparingTo("2.70");
        assertThat(plan.firstTargetPrice()).isEqualByComparingTo("10.27");
        assertThat(plan.secondTargetPercent()).isEqualByComparingTo("4.80");
        assertThat(plan.secondTargetPrice()).isEqualByComparingTo("10.48");
        assertThat(plan.hardStopPercent()).isEqualByComparingTo("2.80");
        assertThat(plan.hardStopPrice()).isEqualByComparingTo("9.72");
        assertThat(plan.firstReductionRatio()).isEqualByComparingTo("0.50");
        assertThat(plan.maxPositionRatio()).isEqualByComparingTo("0.3333");
        assertThat(plan.maxT2PositionRatio()).isEqualByComparingTo("0");
        assertThat(plan.normalExitDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(plan.normalExitTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(plan.absoluteExitDate()).isEqualTo(plan.normalExitDate());
        assertThat(plan.absoluteExitTime()).isEqualTo(plan.normalExitTime());
        assertThat(plan.openScenarios()).extracting(ShortTermOpenScenario::code)
                .containsExactly("HIGH_OPEN", "FLAT_OPEN", "LOW_OPEN");
        assertThat(plan.t2ExtensionConditions()).isEmpty();
        assertThat(plan.openScenarios()).allSatisfy(scenario ->
                assertThat(scenario.action()).contains("10:00", "退出"));
        assertThat(plan.openScenarios().get(0).action()).contains("至少一半", "移动止损");
        assertThat(plan.openScenarios().get(1).action()).contains("未冲高");
        assertThat(plan.riskWarnings()).anySatisfy(warning ->
                assertThat(warning).contains("T+1", "当日无法卖出"));
        assertThat(plan.openScenarios().get(2).action()).contains("次一交易日");
        assertThat(plan.openScenarios().get(2).action()).doesNotContain("当日卖出");
    }

    @Test
    void clampsLowVolatilityToConfiguredFloors() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("1.00", null),
                rules()
        );

        assertThat(plan.firstTargetPercent()).isEqualByComparingTo("2.50");
        assertThat(plan.secondTargetPercent()).isEqualByComparingTo("4.50");
        assertThat(plan.hardStopPercent()).isEqualByComparingTo("2.50");
        assertThat(plan.firstTargetPrice()).isEqualByComparingTo("10.25");
        assertThat(plan.secondTargetPrice()).isEqualByComparingTo("10.45");
        assertThat(plan.hardStopPrice()).isEqualByComparingTo("9.75");
    }

    @Test
    void clampsHighVolatilityToConfiguredCaps() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("10.00", "9.20"),
                rules()
        );

        assertThat(plan.firstTargetPercent()).isEqualByComparingTo("4.00");
        assertThat(plan.secondTargetPercent()).isEqualByComparingTo("7.00");
        assertThat(plan.hardStopPercent()).isEqualByComparingTo("4.50");
        assertThat(plan.firstTargetPrice()).isEqualByComparingTo("10.40");
        assertThat(plan.secondTargetPrice()).isEqualByComparingTo("10.70");
        assertThat(plan.hardStopPrice()).isEqualByComparingTo("9.55");
    }

    @Test
    void blocksPlanInsteadOfInventingPricesWhenReferencePriceIsMissing() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                null,
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.72"),
                rules()
        );

        assertThat(plan.status()).isEqualTo("BLOCKED");
        assertThat(plan.blockedReasons())
                .contains("缺少可验证的参考入场价，未生成目标价和止损价");
        assertThat(plan.referenceEntryPrice()).isNull();
        assertThat(plan.firstTargetPrice()).isNull();
        assertThat(plan.secondTargetPrice()).isNull();
        assertThat(plan.hardStopPrice()).isNull();
        assertThat(plan.analysisBasis()).contains("缺少可验证的参考入场价，未生成目标价和止损价");
    }

    @Test
    void ignoresSupportAtOrAboveEntryAndKeepsHardStopStrictlyBelowEntry() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "10.20"),
                rules()
        );

        assertThat(plan.hardStopPrice()).isEqualByComparingTo("9.67");
        assertThat(plan.hardStopPrice()).isLessThan(plan.referenceEntryPrice());
        assertThat(plan.hardStopPercent()).isEqualByComparingTo("3.30");
        assertThat(plan.analysisBasis()).anySatisfy(item ->
                assertThat(item).contains("10.20", "未用于硬止损"));
    }

    @Test
    void derivesDisplayedStopPercentFromRoundedReferenceAndStopPrices() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.03"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.74"),
                rules()
        );

        BigDecimal displayedPercent = plan.referenceEntryPrice()
                .subtract(plan.hardStopPrice())
                .multiply(bd("100"))
                .divide(plan.referenceEntryPrice(), 2, RoundingMode.HALF_UP);

        assertThat(plan.hardStopPrice()).isLessThan(plan.referenceEntryPrice());
        assertThat(plan.hardStopPercent()).isEqualByComparingTo(displayedPercent);
    }

    @Test
    void validUntilRepresentsExclusive1450ExecutionBoundary() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.72"),
                rules()
        );

        assertThat(plan.validUntil()).isEqualTo(Instant.parse("2026-07-23T06:50:00Z"));
    }

    @Test
    void blocksPlanInsteadOfInventingVolatilityWhenAtrIsMissing() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport(null, "9.72"),
                rules()
        );

        assertThat(plan.status()).isEqualTo("BLOCKED");
        assertThat(plan.blockedReasons())
                .contains("缺少完成 K 线计算的 ATR14，未生成目标价和止损价");
        assertThat(plan.referenceEntryPrice()).isEqualByComparingTo("10.00");
        assertThat(plan.firstTargetPrice()).isNull();
        assertThat(plan.secondTargetPrice()).isNull();
        assertThat(plan.hardStopPrice()).isNull();
        assertThat(plan.analysisBasis()).contains("缺少完成 K 线计算的 ATR14，未生成目标价和止损价");
    }

    @Test
    void blockedPlanUsesIndependentNonExecutableRiskWarnings() {
        ShortTermTradePlan plan = service.blocked(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.72"),
                rules(),
                List.of("行情覆盖不足", "尾盘报价已过期")
        );

        assertThat(plan.status()).isEqualTo("BLOCKED");
        assertThat(plan.blockedReasons()).containsExactly("行情覆盖不足", "尾盘报价已过期");
        assertThat(plan.riskWarnings()).containsExactly(
                "当前计划未通过执行闸门，仅保留风险依据，不构成可执行交易建议。");
        assertThat(String.join("；", plan.riskWarnings()))
                .doesNotContain("T+1", "T+2", "仓位", "入场", "目标", "退出", "截止");
    }

    @Test
    void legacyHoldingConfigurationCannotRestoreT2OrAfternoonExit() {
        OvernightRuleSet base = rules();
        OvernightRuleSet longerHolding = new OvernightRuleSet(
                base.entryStart(),
                base.entryEnd(),
                base.normalExitTime(),
                5,
                base.maxPositionRatio(),
                base.maxT2PositionRatio(),
                base.firstTargetFloor(),
                base.firstTargetCap(),
                base.secondTargetFloor(),
                base.secondTargetCap(),
                base.stopFloor(),
                base.stopCap(),
                base.trailingDrawdownPercent()
        );

        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.72"),
                longerHolding
        );

        assertThat(plan.absoluteExitDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(plan.absoluteExitTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(plan.maxT2PositionRatio()).isEqualByComparingTo("0");
        assertThat(plan.t2ExtensionConditions()).isEmpty();
    }

    @Test
    void resolvesNextTradingMorningAcrossWeekendsAndVerifiedHolidays() {
        for (LocalDate[] dates : List.of(
                new LocalDate[] { LocalDate.of(2026, 7, 24), LocalDate.of(2026, 7, 27) },
                new LocalDate[] { LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 8) })) {
            ShortTermTradePlan plan = service.create(dates[0], bd("10"), bd("9.9"), bd("10.02"),
                    technicalWithAtrAndSupport("3", "9.72"), rules());
            assertThat(plan.status()).isEqualTo("ACTIONABLE");
            assertThat(plan.normalExitDate()).isEqualTo(dates[1]);
            assertThat(plan.absoluteExitDate()).isEqualTo(dates[1]);
        }
    }

    @Test
    void blocksWhenNextTradingDayIsOutsideVerifiedCalendar() {
        for (LocalDate date : new LocalDate[] { null, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 4) }) {
            ShortTermTradePlan plan = service.create(date, bd("10"), bd("9.9"), bd("10.02"),
                    technicalWithAtrAndSupport("3", "9.72"), rules());
            assertThat(plan.status()).isEqualTo("BLOCKED");
            assertThat(plan.normalExitDate()).isNull();
            assertThat(plan.absoluteExitDate()).isNull();
            assertThat(plan.blockedReasons()).anyMatch(reason -> reason.contains("交易日历"));
        }
    }

    @Test
    void usesConfiguredMorningDeadlineInEveryOpenScenario() {
        OvernightRuleSet base = rules();
        OvernightRuleSet morning = new OvernightRuleSet(base.entryStart(), base.entryEnd(), LocalTime.of(10, 30),
                base.maxHoldingTradingDays(), base.maxPositionRatio(), base.maxT2PositionRatio(),
                base.firstTargetFloor(), base.firstTargetCap(), base.secondTargetFloor(), base.secondTargetCap(),
                base.stopFloor(), base.stopCap(), base.trailingDrawdownPercent());
        ShortTermTradePlan plan = service.create(LocalDate.of(2026, 7, 23), bd("10"), bd("9.9"), bd("10.02"),
                technicalWithAtrAndSupport("3", "9.72"), morning);
        assertThat(plan.normalExitTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(plan.absoluteExitTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(plan.openScenarios()).allSatisfy(scenario ->
                assertThat(scenario.action()).contains("10:30").doesNotContain("14:50", "延长"));
    }

    private ShortTermTechnicalSnapshot technicalWithAtrAndSupport(String atr14Percent, String support) {
        return new ShortTermTechnicalSnapshot(
                LocalDate.of(2026, 7, 23),
                bd("9.95"),
                bd("9.88"),
                bd("9.80"),
                bd("9.70"),
                bd("0.30"),
                bd("0.20"),
                bd("10.05"),
                bd("10.20"),
                bd("-0.50"),
                bd("8.00"),
                bd("10.50"),
                bd("8.80"),
                bd("1.20"),
                bd("1.10"),
                bd("65"),
                bd("58"),
                bd("2.04"),
                bd("4.76"),
                bd("2.00"),
                4,
                "右侧早期确认",
                ShortTermGoldenCrossSnapshot.unavailable(),
                bd(atr14Percent),
                bd(support)
        );
    }

    private OvernightRuleSet rules() {
        return new OvernightRuleSet(
                LocalTime.of(14, 45),
                LocalTime.of(14, 56, 59),
                LocalTime.of(14, 50),
                2,
                bd("0.3333"),
                bd("0.50"),
                bd("2.5"),
                bd("4.0"),
                bd("4.5"),
                bd("7.0"),
                bd("2.5"),
                bd("4.5"),
                bd("2.0")
        );
    }

    private BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}

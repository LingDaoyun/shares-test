package com.aistock.research.shortterm;

import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermTradePlanServiceTest {

    private final ShortTermTradePlanService service = new ShortTermTradePlanService(new TradingClockService());

    @Test
    void createsClampedT1T2Plan() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.72"),
                rules()
        );

        assertThat(plan.strategyLabel()).isEqualTo("隔夜超短波段");
        assertThat(plan.status()).isEqualTo("ACTIONABLE");
        assertThat(plan.firstTargetPercent()).isEqualByComparingTo("2.70");
        assertThat(plan.firstTargetPrice()).isEqualByComparingTo("10.27");
        assertThat(plan.secondTargetPercent()).isEqualByComparingTo("4.80");
        assertThat(plan.secondTargetPrice()).isEqualByComparingTo("10.48");
        assertThat(plan.hardStopPercent()).isEqualByComparingTo("2.80");
        assertThat(plan.hardStopPrice()).isEqualByComparingTo("9.72");
        assertThat(plan.firstReductionRatio()).isEqualByComparingTo("0.50");
        assertThat(plan.maxPositionRatio()).isEqualByComparingTo("0.3333");
        assertThat(plan.maxT2PositionRatio()).isEqualByComparingTo("0.50");
        assertThat(plan.normalExitDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(plan.normalExitTime()).isEqualTo(LocalTime.of(14, 50));
        assertThat(plan.absoluteExitDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(plan.absoluteExitTime()).isEqualTo(LocalTime.of(14, 50));
        assertThat(plan.openScenarios()).extracting(ShortTermOpenScenario::code)
                .containsExactly("HIGH_OPEN", "FLAT_OPEN", "LOW_OPEN");
        assertThat(plan.t2ExtensionConditions()).containsExactly(
                "T+1 收益为正",
                "T+1 收盘价高于 MA5",
                "T+1 收盘价高于信号日收盘价",
                "行情新鲜度和风险闸门均未失败"
        );
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
    void validUntilRepresentsExclusive1457ExecutionBoundary() {
        ShortTermTradePlan plan = service.create(
                LocalDate.of(2026, 7, 23),
                bd("10.00"),
                bd("9.90"),
                bd("10.05"),
                technicalWithAtrAndSupport("3.00", "9.72"),
                rules()
        );

        assertThat(plan.validUntil()).isEqualTo(Instant.parse("2026-07-23T06:57:00Z"));
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
    void keepsAbsoluteDeadlineAtT2EvenWhenHoldingConfigurationIsLonger() {
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

        assertThat(plan.absoluteExitDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(plan.absoluteExitTime()).isEqualTo(LocalTime.of(14, 50));
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

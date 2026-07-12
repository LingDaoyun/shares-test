package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.aistock.research.tradefeedback.TradeSide.BUY;
import static com.aistock.research.tradefeedback.TradeSide.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeLedgerCalculatorTest {

    private final TradeLedgerCalculator calculator = new TradeLedgerCalculator();

    @Test
    void calculatesWeightedCostPartialSaleAndRemainingPosition() {
        List<LedgerFill> fills = List.of(
                new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), decimal("30"), 100),
                new LedgerFill(BUY, at("2026-07-14T01:35:00Z"), decimal("40"), 300),
                new LedgerFill(SELL, at("2026-07-15T01:35:00Z"), decimal("45"), 150));

        TradeLedgerSummary result = calculator.calculate(fills, decimal("44"));

        assertThat(result.positionQuantity()).isEqualTo(250);
        assertThat(result.averageCost()).isEqualByComparingTo("37.5");
        assertThat(result.realizedProfit()).isEqualByComparingTo("1125");
        assertThat(result.unrealizedProfit()).isEqualByComparingTo("1625");
        assertThat(result.totalProfit()).isEqualByComparingTo("2750");
    }

    @Test
    void usesRealizedProfitAsTotalAfterPositionIsClosedWithoutLatestPrice() {
        List<LedgerFill> fills = List.of(
                new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), decimal("35"), 200),
                new LedgerFill(BUY, at("2026-07-14T01:35:00Z"), decimal("40"), 200),
                new LedgerFill(SELL, at("2026-07-15T01:35:00Z"), decimal("45"), 150),
                new LedgerFill(SELL, at("2026-07-16T01:35:00Z"), decimal("44"), 250));

        TradeLedgerSummary result = calculator.calculate(fills, null);

        assertThat(result.positionQuantity()).isZero();
        assertThat(result.realizedProfit()).isEqualByComparingTo("2750");
        assertThat(result.unrealizedProfit()).isNull();
        assertThat(result.totalProfit()).isEqualByComparingTo("2750");
    }

    @Test
    void rejectsSaleAboveAvailablePosition() {
        assertThatThrownBy(() -> calculator.calculate(List.of(
                new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), decimal("30"), 100),
                new LedgerFill(SELL, at("2026-07-13T02:35:00Z"), decimal("31"), 101)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("卖出股数超过当前持仓");
    }

    @Test
    void sortsFillsBeforeCheckingForAnOversoldPrefix() {
        assertThatThrownBy(() -> calculator.calculate(List.of(
                new LedgerFill(BUY, at("2026-07-13T02:35:00Z"), decimal("30"), 100),
                new LedgerFill(SELL, at("2026-07-13T01:35:00Z"), decimal("31"), 100)), decimal("31")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("卖出股数超过当前持仓");
    }

    @Test
    void rejectsNonPositiveFillValuesAndLatestPrice() {
        assertThatThrownBy(() -> calculator.calculate(List.of(
                new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), BigDecimal.ZERO, 100)), decimal("31")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成交价格必须大于零");

        assertThatThrownBy(() -> calculator.calculate(List.of(
                new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), decimal("30"), 0)), decimal("31")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成交股数必须为正整数");

        assertThatThrownBy(() -> calculator.calculate(List.<LedgerFill>of(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最新价格必须大于零");
    }

    @Test
    void rejectsMissingOrInvalidEntitySidesWithIllegalArgumentException() {
        TradeFillEntity missingSide = TradeFillEntity.create(
                "fill-1", "case-1", null, at("2026-07-13T01:35:00Z"), decimal("30"), 100,
                at("2026-07-13T01:35:00Z"));

        assertThatThrownBy(() -> calculator.calculate(List.of(missingSide), decimal("31")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成交方向必须为 BUY 或 SELL");

        assertThatThrownBy(() -> calculator.calculate(Arrays.asList(
                new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), decimal("30"), 100), null
        ), decimal("31")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成交记录不能为空");
    }

    private Instant at(String value) {
        return Instant.parse(value);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

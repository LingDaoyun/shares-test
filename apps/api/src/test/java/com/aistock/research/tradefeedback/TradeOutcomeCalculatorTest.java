package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradeOutcomeCalculatorTest {

    private final TradeOutcomeCalculator calculator = new TradeOutcomeCalculator();

    @Test
    void evaluatesHorizonsByShanghaiTradingRowsAndKeepsFutureMetricsNull() {
        List<MarketBar> rows = List.of(
                bar("2026-07-13", "10.5", "10.8", "9.8"),
                bar("2026-07-14", "11.0", "11.2", "10.1"),
                bar("2026-07-15", "9.5", "11.4", "9.0"),
                bar("2026-07-16", "10.8", "11.5", "8.8"),
                bar("2026-07-17", "12.0", "12.2", "8.7"));

        List<OutcomeResult> result = calculator.evaluateRecommendation(
                decimal("10"), rows, Instant.parse("2026-07-10T07:00:00Z"));

        assertThat(outcome(result, "T1").returnPct()).isEqualByComparingTo("5.0000");
        assertThat(outcome(result, "T5").returnPct()).isEqualByComparingTo("20.0000");
        assertThat(outcome(result, "T5").maxRunupPct()).isEqualByComparingTo("22.0000");
        assertThat(outcome(result, "T5").maxDrawdownPct()).isEqualByComparingTo("-13.0000");
        assertThat(outcome(result, "T20").status()).isEqualTo("PENDING");
        assertThat(outcome(result, "T20"))
                .extracting(OutcomeResult::evaluationPrice, OutcomeResult::evaluationDate,
                        OutcomeResult::returnPct, OutcomeResult::maxRunupPct, OutcomeResult::maxDrawdownPct)
                .containsOnlyNulls();
    }

    @Test
    void excludesRowsOnTheShanghaiRecommendationDateEvenAcrossUtcDateBoundary() {
        List<MarketBar> rows = List.of(
                bar("2026-07-11", "9", "9.5", "8.5"),
                bar("2026-07-13", "11", "11.5", "10.5"));

        List<OutcomeResult> result = calculator.evaluateRecommendation(
                decimal("10"), rows, Instant.parse("2026-07-10T16:30:00Z"));

        assertThat(outcome(result, "T1").evaluationDate()).isEqualTo(LocalDate.parse("2026-07-13"));
        assertThat(outcome(result, "T1").returnPct()).isEqualByComparingTo("10.0000");
    }

    @Test
    void leavesEveryFutureHorizonPendingForValidEmptyHistory() {
        List<OutcomeResult> result = calculator.evaluateRecommendation(
                decimal("10"), List.of(), Instant.parse("2026-07-10T07:00:00Z"));

        assertThat(result).extracting(OutcomeResult::horizon).containsExactly("T1", "T5", "T20");
        assertThat(result).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("PENDING");
            assertThat(item.evaluationPrice()).isNull();
            assertThat(item.evaluationDate()).isNull();
            assertThat(item.returnPct()).isNull();
            assertThat(item.maxRunupPct()).isNull();
            assertThat(item.maxDrawdownPct()).isNull();
        });
    }

    @Test
    void calculatesExecutionReturnFromCumulativeCashFlowsOverGrossBuys() {
        List<LedgerFill> holding = List.of(
                fill(TradeSide.BUY, "2026-07-13T01:30:00Z", "10", 100),
                fill(TradeSide.BUY, "2026-07-14T01:30:00Z", "20", 50),
                fill(TradeSide.SELL, "2026-07-15T01:30:00Z", "15", 40));

        OutcomeResult current = calculator.evaluateExecution(
                holding, 110, decimal("16"), LocalDate.parse("2026-07-16"));

        assertThat(current.horizon()).isEqualTo("CURRENT");
        assertThat(current.returnPct()).isEqualByComparingTo("18.0000");
        assertThat(current.evaluationPrice()).isEqualByComparingTo("16");
        assertThat(current.evaluationDate()).isEqualTo(LocalDate.parse("2026-07-16"));

        List<LedgerFill> closed = List.of(
                fill(TradeSide.BUY, "2026-07-13T01:30:00Z", "10", 100),
                fill(TradeSide.SELL, "2026-07-15T01:30:00Z", "12", 100));

        OutcomeResult closedResult = calculator.evaluateExecution(
                closed, 0, null, LocalDate.parse("2026-07-20"));

        assertThat(closedResult.horizon()).isEqualTo("CLOSED");
        assertThat(closedResult.returnPct()).isEqualByComparingTo("20.0000");
        assertThat(closedResult.evaluationDate()).isEqualTo(LocalDate.parse("2026-07-15"));
    }

    private MarketBar bar(String date, String close, String high, String low) {
        return new MarketBar(LocalDate.parse(date), decimal(close), decimal(high), decimal(low));
    }

    private LedgerFill fill(TradeSide side, String at, String price, long quantity) {
        Instant instant = Instant.parse(at);
        return new LedgerFill(side, instant, decimal(price), quantity, instant);
    }

    private OutcomeResult outcome(List<OutcomeResult> results, String horizon) {
        return results.stream().filter(item -> horizon.equals(item.horizon())).findFirst().orElseThrow();
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

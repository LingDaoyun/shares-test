package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermResearchSetupEvaluatorTest {
    private static final LocalDate START = LocalDate.of(2026, 3, 2);
    private static final TradingClockService CALENDAR = new TradingClockService();
    private final ShortTermResearchSetupEvaluator evaluator = new ShortTermResearchSetupEvaluator();

    @Test
    void identifiesContractionBreakoutWithoutGoldenCross() {
        List<EastMoneyKLine> rows = breakout();
        ShortTermResearchSetup result = evaluate(rows, quote(rows, true), strength());
        assertThat(result.setupType()).isEqualTo("CONTRACTION_BREAKOUT");
        assertThat(result.confirmed()).isTrue();
        assertThat(result.triggerPrice()).isEqualByComparingTo("12.07");
        assertThat(result.invalidationPrice()).isLessThan(result.triggerPrice());
        assertThat(evaluator.qualifiesWithoutGoldenCross(rows, quote(rows, true), null, null, null)).isTrue();
        assertThat(result.evidence()).anyMatch(value -> value.contains("量化近似"));
    }

    @Test
    void identifiesLowVolumeRetreatAndRecoveryInAnEstablishedTrend() {
        List<EastMoneyKLine> rows = pullback();
        ShortTermResearchSetup result = evaluate(rows, quote(rows, true), strength());
        assertThat(result.setupType()).isEqualTo("TREND_PULLBACK");
        assertThat(result.confirmed()).isTrue();
        assertThat(evaluator.qualifiesWithoutGoldenCross(rows, quote(rows, true), null, null, null)).isTrue();
    }

    @Test
    void doesNotQualifyAnOrdinaryWeakTrendAsAPullback() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            BigDecimal price = bd("12").subtract(bd("0.02").multiply(BigDecimal.valueOf(i)));
            rows.add(row(i, price, bd("0.20"), bd("100000")));
        }
        ShortTermResearchSetup result = evaluate(rows, quote(rows, true), strength());
        assertThat(result.matched()).isFalse();
        assertThat(evaluator.qualifiesWithoutGoldenCross(rows, quote(rows, true), null, null, null)).isFalse();
    }

    @Test
    void missingRelativeStrengthReceivesNoContributionAndCannotConfirm() {
        List<EastMoneyKLine> rows = breakout();
        ShortTermResearchSetup missing = evaluate(rows, quote(rows, true), null);
        ShortTermResearchSetup available = evaluate(rows, quote(rows, true), strength());
        assertThat(missing.relativeStrengthScore()).isNull();
        assertThat(missing.relativeStrengthContribution()).isEqualByComparingTo("0");
        assertThat(available.score().subtract(missing.score())).isEqualByComparingTo("16");
        assertThat(missing.state()).isEqualTo("WATCH");
        assertThat(missing.executionEvidenceReady()).isFalse();
        assertThat(missing.dataGaps()).anyMatch(value -> value.contains("相对强度"));
    }

    @Test
    void roundedContributionsExactlyReconcileToTotal() {
        List<EastMoneyKLine> rows = breakout();
        ShortTermResearchSetup result = evaluate(rows, quote(rows, true), strength());
        assertThat(result.score()).isEqualByComparingTo(result.trendContribution()
                .add(result.relativeStrengthContribution()).add(result.setupContribution())
                .add(result.volumePriceContribution()));
        assertThat(result.trendContribution()).isEqualByComparingTo("25.00");
        assertThat(result.relativeStrengthContribution()).isEqualByComparingTo("16.00");
    }

    @Test
    void futureCandlesCannotChangeHistoricalSetupOrScore() {
        List<EastMoneyKLine> rows = breakout();
        EastMoneyQuote quote = quote(rows, true);
        ShortTermResearchSetup original = evaluate(rows, quote, strength());
        rows.add(row(66, bd("999"), bd("5"), bd("9999999")));
        ShortTermResearchSetup result = evaluate(rows, quote, strength());
        assertThat(result.setupType()).isEqualTo(original.setupType());
        assertThat(result.score()).isEqualByComparingTo(original.score());
        assertThat(result.triggerPrice()).isEqualByComparingTo(original.triggerPrice());
    }

    @Test
    void unfinishedDailyCandleCannotSupplyConfirmationOrLeakItsOhlcv() {
        List<EastMoneyKLine> rows = breakout();
        EastMoneyQuote intraday = quote(rows, false);
        ShortTermResearchSetup original = evaluate(rows, intraday, strength());
        rows.set(rows.size() - 1, row(65, bd("999"), bd("5"), bd("9999999")));
        ShortTermResearchSetup changed = evaluate(rows, intraday, strength());
        assertThat(original.setupType()).isEqualTo("CONTRACTION_BREAKOUT");
        assertThat(original.state()).isEqualTo("WATCH");
        assertThat(changed).isEqualTo(original);
    }

    @Test
    void unknownMarketTimestampDoesNotTreatFetchTimeAsCompletion() {
        List<EastMoneyKLine> rows = breakout();
        EastMoneyQuote known = quote(rows, true);
        EastMoneyQuote unknown = new EastMoneyQuote(known.symbol(), known.name(), known.market(),
                known.industry(), known.latestPrice(), known.changePercent(), known.turnoverRate(),
                known.volume(), known.amount(), null, null, null, "test", "test", known.fetchedAt(),
                known.tradeDate(), null);
        ShortTermResearchSetup result = evaluate(rows, unknown, strength());
        assertThat(result.confirmed()).isFalse();
        assertThat(result.executionEvidenceReady()).isFalse();
    }

    @Test
    void sortsAndDeduplicatesIdenticalDatesWithoutChangingTheResult() {
        List<EastMoneyKLine> rows = breakout();
        EastMoneyQuote quote = quote(rows, true);
        ShortTermResearchSetup original = evaluate(rows, quote, strength());
        rows.add(rows.get(30));
        Collections.reverse(rows);
        assertThat(evaluate(rows, quote, strength())).isEqualTo(original);
    }

    @Test
    void insufficientCompletedHistoryCannotQualifyFromOptimisticQuote() {
        List<EastMoneyKLine> rows = breakout();
        EastMoneyQuote quote = quote(rows, true);
        ShortTermResearchSetup result = evaluate(rows.subList(40, rows.size()), quote, strength());
        assertThat(result.historicalStructureCurrent()).isTrue();
        assertThat(result.state()).isEqualTo("UNAVAILABLE");
        assertThat(result.matched()).isFalse();
        assertThat(result.dataGaps()).isNotEmpty();
    }

    @Test
    void excessiveBreakoutExtensionDoesNotQualify() {
        List<EastMoneyKLine> rows = breakout();
        rows.set(rows.size() - 1, row(65, bd("14.50"), bd("0.10"), bd("160000")));
        assertThat(evaluator.qualifiesWithoutGoldenCross(rows, quote(rows, true), null, null, null)).isFalse();
    }

    @Test
    void validIntradayProxyCanProceedToTailReviewWithoutClaimingCompletedConfirmation() {
        List<EastMoneyKLine> rows = breakout();
        ShortTermResearchSetup result = evaluate(rows, quote(rows, false), strength());
        assertThat(result.executionEvidenceReady()).isTrue();
        assertThat(result.confirmed()).isFalse();
    }

    @Test
    void freshQuoteCannotMakeWeeksOldHistoricalStructureExecutable() {
        List<EastMoneyKLine> rows = breakout();
        EastMoneyQuote original = quote(rows, false);
        // Keep the exact same price and volume evidence, but move the live quote
        // weeks beyond the newest completed daily bar returned by the provider.
        LocalDate liveDate = LocalDate.of(2026, 7, 23);
        Instant liveTimestamp = Instant.parse(liveDate + "T06:47:00Z");
        EastMoneyQuote liveQuote = new EastMoneyQuote(original.symbol(), original.name(), original.market(),
                original.industry(), original.latestPrice(), original.changePercent(), original.turnoverRate(),
                original.volume(), original.amount(), null, null, null, "test", "test", liveTimestamp,
                liveDate, liveTimestamp, null, original.openPrice(), original.highPrice(), original.lowPrice(),
                original.realtimeVolumeRatio());
        ShortTermResearchSetup result = evaluate(rows.subList(0, rows.size() - 1), liveQuote, strength());

        assertThat(result.setupType()).isEqualTo("CONTRACTION_BREAKOUT");
        assertThat(result.executionEvidenceReady()).isFalse();
        assertThat(result.historicalStructureCurrent()).isFalse();
        assertThat(result.confirmed()).isFalse();
        assertThat(result.dataGaps()).anyMatch(value -> value.contains("日线未更新"));
    }

    @ParameterizedTest
    @CsvSource({"2026-07-27,2026-07-24", "2026-10-08,2026-09-30"})
    void lastTradingDayRemainsUsableAcrossWeekendsAndHolidays(String live, String previous) {
        List<EastMoneyKLine> source = breakout();
        LocalDate liveDate = LocalDate.parse(live);
        LocalDate cursor = LocalDate.parse(previous);
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int i = source.size() - 2; i >= 0; i--) {
            EastMoneyKLine row = source.get(i);
            rows.add(new EastMoneyKLine(row.symbol(), cursor, row.open(), row.close(), row.high(), row.low(),
                    row.volume(), row.amount()));
            cursor = CALENDAR.previousTradingDay(cursor);
        }
        EastMoneyKLine current = source.get(source.size() - 1);
        rows.add(new EastMoneyKLine(current.symbol(), liveDate, current.open(), current.close(), current.high(),
                current.low(), current.volume(), current.amount()));

        ShortTermResearchSetup result = evaluate(rows, quote(rows, false), strength());

        assertThat(result.setupType()).isEqualTo("CONTRACTION_BREAKOUT");
        assertThat(result.executionEvidenceReady()).isTrue();
        assertThat(result.historicalStructureCurrent()).isTrue();
        assertThat(result.dataGaps()).noneMatch(value -> value.contains("日线未更新"));
    }

    @Test
    void closedQuoteCannotExecuteWithOnlyThePreviousTradingDaysHistory() {
        List<EastMoneyKLine> rows = breakout();
        ShortTermResearchSetup result = evaluate(rows.subList(0, rows.size() - 1), quote(rows, true), strength());

        assertThat(result.executionEvidenceReady()).isFalse();
        assertThat(result.historicalStructureCurrent()).isFalse();
        assertThat(result.confirmed()).isFalse();
        assertThat(result.dataGaps()).anyMatch(value -> value.contains("日线未更新"));
    }

    @Test
    void unknownHolidayCalendarCannotAuthorizeHistoricalStructure() {
        List<EastMoneyKLine> rows = breakout().stream().map(row -> new EastMoneyKLine(row.symbol(),
                row.tradeDate().plusYears(1), row.open(), row.close(), row.high(), row.low(), row.volume(), row.amount()))
                .toList();
        ShortTermResearchSetup result = evaluate(rows, quote(rows, true), strength());

        assertThat(result.historicalStructureCurrent()).isFalse();
        assertThat(result.executionEvidenceReady()).isFalse();
        assertThat(result.confirmed()).isFalse();
        assertThat(result.dataGaps()).anyMatch(value -> value.contains("交易日历未核实"));
    }

    @Test
    void conflictingHistoryBlocksExecutionEvidenceEvenWhenTheSetupStillLooksStrong() {
        List<EastMoneyKLine> rows = breakout();
        EastMoneyQuote quote = quote(rows, false);
        rows.add(row(10, bd("10.4"), bd("0.30"), bd("100000")));
        ShortTermResearchSetup result = evaluate(rows, quote, strength());
        assertThat(result.executionEvidenceReady()).isFalse();
        assertThat(result.confirmed()).isFalse();
        assertThat(result.dataGaps()).anyMatch(value -> value.contains("冲突"));
    }

    @Test
    void reusesDatedConfirmedSupportEvidenceWithoutInventingAnotherPattern() {
        List<EastMoneyKLine> rows = breakout();
        rows.set(rows.size() - 1, row(65, bd("11.98"), bd("0.10"), bd("160000")));
        EastMoneyQuote quote = quote(rows, true);
        ShortTermSupportReversalSignal support = new ShortTermSupportReversalSignal("CONFIRMED", "承接确认",
                bd("88"), bd("50"), bd("15"), bd("10"), bd("85"), "MA10", bd("11.80"),
                true, true, true, true, false, List.of(), List.of());
        ShortTermTechnicalSnapshot technical = technical(quote.tradeDate(), ShortTermGoldenCrossSnapshot.unavailable())
                .withSupportReversal(support);
        ShortTermResearchSetup result = evaluator.evaluate(rows, quote, technical, null, strength(), null);
        assertThat(result.setupType()).isEqualTo("SUPPORT_REVERSAL");
        assertThat(result.confirmed()).isTrue();
        assertThat(result.executionEvidenceReady()).isTrue();
    }

    @Test
    void staleGoldenCrossCannotBeConfirmedByAnIncorrectRecentAgeField() {
        List<EastMoneyKLine> rows = breakout();
        rows.set(rows.size() - 1, row(65, bd("11.98"), bd("0.10"), bd("160000")));
        EastMoneyQuote quote = quote(rows, true);
        ShortTermGoldenCrossSnapshot cross = new ShortTermGoldenCrossSnapshot("test", "CONFIRMED", "金叉确认",
                START.plusDays(40), 1, bd("1"), "RISING", "BULLISH", 1, "CONFIRMED");
        ShortTermResearchSetup result = evaluator.evaluate(rows, quote, technical(quote.tradeDate(), cross), null, strength(), null);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.executionEvidenceReady()).isFalse();
    }

    @Test
    void moderateButUnconfirmedVolumeCannotBecomeExecutionReadyThroughHighOtherScores() {
        List<EastMoneyKLine> rows = breakout();
        rows.set(rows.size() - 1, row(65, bd("12.12"), bd("0.10"), bd("100000")));
        ShortTermResearchSetup result = evaluate(rows, quote(rows, true), strength());
        assertThat(result.setupType()).isEqualTo("CONTRACTION_BREAKOUT");
        assertThat(result.executionEvidenceReady()).isFalse();
    }

    private ShortTermTechnicalSnapshot technical(LocalDate date, ShortTermGoldenCrossSnapshot cross) {
        return new ShortTermTechnicalSnapshot(date, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                0, "UNAVAILABLE", cross, null, null);
    }

    private ShortTermResearchSetup evaluate(List<EastMoneyKLine> rows, EastMoneyQuote quote,
                                           ShortTermRelativeStrength strength) {
        return evaluator.evaluate(rows, quote, null, null, strength, null);
    }

    private List<EastMoneyKLine> breakout() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            BigDecimal close = bd("10").add(bd("0.03").multiply(BigDecimal.valueOf(i)));
            rows.add(row(i, close, i >= 60 ? bd("0.07") : bd("0.30"), bd("100000")));
        }
        rows.add(new EastMoneyKLine("600001", date(65), bd("12.02"), bd("12.12"),
                bd("12.16"), bd("11.98"), bd("160000"), bd("193920000")));
        return rows;
    }

    private List<EastMoneyKLine> pullback() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int i = 0; i < 66; i++) {
            rows.add(row(i, bd("10").add(bd("0.06").multiply(BigDecimal.valueOf(i))),
                    bd("0.25"), bd("100000")));
        }
        rows.add(row(66, bd("13.82"), bd("0.10"), bd("70000")));
        rows.add(row(67, bd("13.75"), bd("0.10"), bd("65000")));
        rows.add(row(68, bd("13.68"), bd("0.10"), bd("60000")));
        rows.add(new EastMoneyKLine("600001", date(69), bd("13.72"), bd("13.92"),
                bd("13.97"), bd("13.65"), bd("130000"), bd("180960000")));
        return rows;
    }

    private EastMoneyKLine row(int offset, BigDecimal close, BigDecimal halfRange, BigDecimal volume) {
        return new EastMoneyKLine("600001", date(offset), close.subtract(bd("0.02")), close,
                close.add(halfRange), close.subtract(halfRange), volume, close.multiply(volume).multiply(bd("100")));
    }

    private EastMoneyQuote quote(List<EastMoneyKLine> rows, boolean completed) {
        EastMoneyKLine latest = rows.get(rows.size() - 1);
        Instant timestamp = Instant.parse(latest.tradeDate() + (completed ? "T07:05:00Z" : "T06:45:00Z"));
        return new EastMoneyQuote("600001", "测试", "SH", "电力", latest.close(), bd("1.5"), bd("3"),
                latest.volume(), latest.amount(), null, null, null, "test", "test", timestamp,
                latest.tradeDate(), timestamp, null, latest.open(), latest.high(), latest.low(), bd("1.6"));
    }

    private ShortTermRelativeStrength strength() {
        return new ShortTermRelativeStrength(bd("3"), bd("5"), bd("8"), bd("80"), bd("80"), bd("80"),
                bd("80"), bd("80"), bd("80"), 100, 10, bd("80"), bd("2.4"), List.of());
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }

    private LocalDate date(int offset) { return CALENDAR.tradingDayAfter(START, offset); }
}

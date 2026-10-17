package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.aistock.research.fiveday.FiveDayModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class FiveDayOutcomeCalculatorTest {
    private static final LocalDate SIGNAL = LocalDate.parse("2026-09-07");
    private static final LocalDate ENTRY = LocalDate.parse("2026-09-08");
    private static final LocalDate EXIT = LocalDate.parse("2026-09-14");
    private final TradingClockService calendar = new TradingClockService(Clock.fixed(
            Instant.parse("2026-09-15T08:00:00Z"), ZoneId.of("Asia/Shanghai")));
    private final FiveDayOutcomeCalculator calculator = new FiveDayOutcomeCalculator(calendar);

    @Test
    void entryIsDayOneAndFixedExitUsesTheFifthMarketDayWithCosts() {
        Outcome outcome = evaluate(bars(), EXIT);
        assertThat(outcome.entryDate()).isEqualTo(ENTRY);
        assertThat(outcome.plannedExitDate()).isEqualTo(EXIT);
        assertThat(outcome.fixedExitDate()).isEqualTo(EXIT);
        assertThat(outcome.fixedNetReturnPercent()).isEqualByComparingTo("5.8");
        assertThat(outcome.fixedGrossReturnPercent()).isEqualByComparingTo("6");
        assertThat(outcome.fixedStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void aMissingEntryOpeningCannotBeReplacedByTheNextAvailableDay() {
        List<EastMoneyKLine> rows = new ArrayList<>(bars());
        rows.removeIf(row -> row.tradeDate().equals(ENTRY));
        Outcome outcome = evaluate(rows, EXIT);
        assertThat(outcome.fixedStatus()).isEqualTo("MISSING_DATA");
        assertThat(outcome.entryPrice()).isNull();
        assertThat(outcome.fixedNetReturnPercent()).isNull();
    }

    @Test
    void openingAboveTheFrozenEntryCapIsNotFilled() {
        List<EastMoneyKLine> rows = replace(bars(), bar(ENTRY, "10.21", "10.1"));
        Outcome outcome = evaluate(rows, EXIT);
        assertThat(outcome.fixedStatus()).isEqualTo("ENTRY_NOT_FILLED");
        assertThat(outcome.entryPrice()).isNull();
        assertThat(outcome.riskNetReturnPercent()).isNull();
    }

    @Test
    void qfqAnchorScalesFrozenStopAndEntryCapToTheStoredSeries() {
        List<EastMoneyKLine> scaled = bars().stream().map(row -> new EastMoneyKLine(
                row.symbol(), row.tradeDate(), row.open().divide(decimal("2")),
                row.close().divide(decimal("2")), row.high().divide(decimal("2")),
                row.low().divide(decimal("2")), row.volume(), row.amount(), row.turnoverRate())).toList();
        Outcome outcome = evaluate(scaled, EXIT);
        assertThat(outcome.entryPrice()).isEqualByComparingTo("5");
        assertThat(outcome.fixedNetReturnPercent()).isEqualByComparingTo("5.8");
        assertThat(outcome.riskNetReturnPercent()).isEqualByComparingTo("5.8");
    }

    @Test
    void absentSignalDayAnchorLeavesBothResultsMissing() {
        Outcome outcome = evaluate(bars().stream().filter(row -> !row.tradeDate().equals(SIGNAL)).toList(), EXIT);
        assertThat(outcome.fixedStatus()).isEqualTo("MISSING_DATA");
        assertThat(outcome.entryPrice()).isNull();
    }

    @Test
    void aClosingStopBreachExitsAtNextTradableOpeningAndKeepsFixedResultSeparate() {
        List<EastMoneyKLine> rows = replace(bars(), bar(ENTRY, "10", "9.4"));
        rows = replace(rows, bar(LocalDate.parse("2026-09-09"), "9.35", "10"));
        Outcome outcome = evaluate(rows, EXIT);
        assertThat(outcome.riskExitDate()).isEqualTo(LocalDate.parse("2026-09-09"));
        assertThat(outcome.riskExitPrice()).isEqualByComparingTo("9.35");
        assertThat(outcome.riskNetReturnPercent()).isEqualByComparingTo("-6.7");
        assertThat(outcome.fixedNetReturnPercent()).isEqualByComparingTo("5.8");
    }

    @Test
    void aBlockedFifthDayExitStaysOpenUntilARealLaterMarketDayOpening() {
        List<EastMoneyKLine> rows = replace(bars(), bar(EXIT, "10", "9"));
        Outcome pending = evaluate(rows, EXIT);
        assertThat(pending.fixedStatus()).isEqualTo("EXIT_DEFERRED");
        assertThat(pending.fixedExitPrice()).isNull();
        assertThat(pending.fixedNetReturnPercent()).isNull();
        rows = new ArrayList<>(rows);
        rows.add(bar(LocalDate.parse("2026-09-15"), "9.1", "9.3"));
        Outcome deferred = evaluate(rows, LocalDate.parse("2026-09-15"));
        assertThat(deferred.plannedExitDate()).isEqualTo(EXIT);
        assertThat(deferred.fixedExitDate()).isEqualTo(LocalDate.parse("2026-09-15"));
        assertThat(deferred.fixedExitPrice()).isEqualByComparingTo("9.1");
        assertThat(deferred.fixedStatus()).isEqualTo("COMPLETED_DEFERRED");
    }

    @Test
    void aLimitUpLikeClosingDoesNotBlockASellScenario() {
        Outcome outcome = evaluate(replace(bars(), bar(EXIT, "10", "11")), EXIT);
        assertThat(outcome.fixedStatus()).isEqualTo("COMPLETED");
        assertThat(outcome.fixedExitPrice()).isEqualByComparingTo("11");
        assertThat(outcome.fixedNetReturnPercent()).isEqualByComparingTo("9.8");
    }

    @Test
    void aRiskExitOpeningNearLimitUpIsNotTreatedAsAnUnfillableBuy() {
        List<EastMoneyKLine> rows = replace(bars(), bar(ENTRY, "10", "9.4"));
        rows = replace(rows, bar(LocalDate.parse("2026-09-09"), "10.34", "10"));
        Outcome outcome = evaluate(rows, EXIT);
        assertThat(outcome.riskStatus()).isEqualTo("COMPLETED");
        assertThat(outcome.riskExitDate()).isEqualTo(LocalDate.parse("2026-09-09"));
        assertThat(outcome.riskExitPrice()).isEqualByComparingTo("10.34");
    }

    @Test
    void aMissingPlannedExitIsNotReplacedWithTheFifthAvailableBar() {
        List<EastMoneyKLine> rows = new ArrayList<>(bars());
        rows.removeIf(row -> row.tradeDate().equals(EXIT));
        rows.add(bar(LocalDate.parse("2026-09-15"), "10.4", "10.5"));
        Outcome outcome = evaluate(rows, LocalDate.parse("2026-09-15"));
        assertThat(outcome.fixedStatus()).isEqualTo("MISSING_DATA");
        assertThat(outcome.plannedExitDate()).isEqualTo(EXIT);
        assertThat(outcome.fixedExitDate()).isNull();
    }

    @Test
    void missingAnEarlierCloseBlocksRiskReconstructionButNotKnownFixedPrice() {
        List<EastMoneyKLine> rows = bars().stream()
                .filter(row -> !row.tradeDate().equals(LocalDate.parse("2026-09-09"))).toList();
        Outcome outcome = evaluate(rows, EXIT);
        assertThat(outcome.fixedNetReturnPercent()).isEqualByComparingTo("5.8");
        assertThat(outcome.riskStatus()).isEqualTo("MISSING_DATA");
        assertThat(outcome.riskNetReturnPercent()).isNull();
        assertThat(outcome.maxAdverseExcursionPercent()).isNull();
    }

    private Outcome evaluate(List<EastMoneyKLine> rows, LocalDate asOf) {
        Candidate candidate = candidate("000001", true, true);
        return calculator.evaluate(snapshot(List.of(candidate), true, false, false), candidate, rows, asOf);
    }

    static Candidate candidate(String symbol, boolean flowComplete, boolean matchesFlow) {
        return new Candidate(symbol, "样本", "行业", "MAIN", decimal("10"), decimal("10.2"),
                decimal("9.5"), decimal("7"), decimal("1"), decimal("0"), decimal("2"),
                decimal("1"), decimal("2"), decimal("60000000"), decimal("1000000"),
                decimal("1"), 3, flowComplete, matchesFlow, "https://data.eastmoney.com/",
                decimal("9.5"), decimal("10.2"), ENTRY, EXIT, List.of(), List.of(), List.of());
    }

    static Snapshot snapshot(List<Candidate> candidates, boolean forward, boolean growth, boolean star) {
        return new Snapshot("READY", VERSION, SIGNAL, Instant.parse("2026-09-07T08:00:00Z"),
                forward, growth, star, "日线情景", Rules.defaults(),
                new Coverage(10, candidates.size(), candidates.size(), candidates.size(), 1, 0, 0),
                candidates, List.of());
    }

    private List<EastMoneyKLine> bars() {
        return List.of(bar(SIGNAL, "10", "10"), bar(ENTRY, "10", "10"),
                bar(LocalDate.parse("2026-09-09"), "10", "10"),
                bar(LocalDate.parse("2026-09-10"), "10", "10"),
                bar(LocalDate.parse("2026-09-11"), "10", "10"), bar(EXIT, "10", "10.6"));
    }

    private List<EastMoneyKLine> replace(List<EastMoneyKLine> rows, EastMoneyKLine replacement) {
        return rows.stream().map(row -> row.tradeDate().equals(replacement.tradeDate()) ? replacement : row).toList();
    }

    private EastMoneyKLine bar(LocalDate date, String open, String close) {
        BigDecimal opening = decimal(open), closing = decimal(close);
        return new EastMoneyKLine("000001", date, opening, closing,
                opening.max(closing), opening.min(closing), decimal("100000"), decimal("60000000"), decimal("2"));
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}

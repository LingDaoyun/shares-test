package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermTechnicalSignalEvaluatorTest {

    private final ShortTermTechnicalSignalEvaluator evaluator = new ShortTermTechnicalSignalEvaluator();
    private final ShortTermRuleSet ruleSet = new ShortTermRuleSet(
            6000,
            60,
            new BigDecimal("80000000"),
            new BigDecimal("1.15"),
            new BigDecimal("4"),
            new BigDecimal("8"),
            new BigDecimal("58")
    );

    @Test
    void acceptsOnlyACompletedRecentGoldenCrossWithTheProductionRightEarlyConfirmation() {
        List<EastMoneyKLine> rows = confirmedReplayRows("600201");

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(rows.size() - 1).close(),
                false,
                ruleSet
        );

        assertThat(result.snapshot().rightSideSignal()).isEqualTo("右侧早期确认");
        assertThat(result.snapshot().goldenCross().state()).isEqualTo("CONFIRMED");
        assertThat(result.snapshot().goldenCross().tradingDaysSinceCross()).isZero();
        assertThat(result.eligibleForOvernightValidation()).isTrue();
    }

    @Test
    void rejectsAnUnfinishedSignalDayGoldenCrossAsForming() {
        List<EastMoneyKLine> rows = formingReplayRows("600202");

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(rows.size() - 1).close(),
                false,
                ruleSet
        );

        assertThat(result.snapshot().goldenCross().state()).isEqualTo("FORMING");
        assertThat(result.eligibleForOvernightValidation()).isFalse();
    }

    @Test
    void rejectsAnOlderEstablishedGoldenCross() {
        List<EastMoneyKLine> rows = establishedReplayRows("600203");

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(rows.size() - 1).close(),
                false,
                ruleSet
        );

        assertThat(result.snapshot().goldenCross().state()).isEqualTo("ESTABLISHED");
        assertThat(result.eligibleForOvernightValidation()).isFalse();
    }

    @Test
    void rejectsRowsWithoutAGoldenCross() {
        List<EastMoneyKLine> rows = noneReplayRows("600204");

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(rows.size() - 1).close(),
                false,
                ruleSet
        );

        assertThat(result.snapshot().goldenCross().state()).isEqualTo("NONE");
        assertThat(result.eligibleForOvernightValidation()).isFalse();
    }

    @Test
    void returnsTodayVolumeAndTheImmediatelyPreviousThreeDayAverage() {
        List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600205"));
        int size = rows.size();
        replaceVolume(rows, size - 4, "100000");
        replaceVolume(rows, size - 3, "200000");
        replaceVolume(rows, size - 2, "300000");
        replaceVolume(rows, size - 1, "300000");

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(rows.size() - 1).close(),
                false,
                ruleSet
        );

        assertThat(result.snapshot().todayVolume()).isEqualByComparingTo("300000.00");
        assertThat(result.snapshot().averageVolume3()).isEqualByComparingTo("200000.00");
        assertThat(result.snapshot().volumeRatio3()).isEqualByComparingTo("1.50");
    }

    @Test
    void comparesSnapshotVolumeWithThreeCompletedBarsWhenCurrentDailyBarIsAbsent() {
        List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600208"));
        int size = rows.size();
        replaceVolume(rows, size - 4, "50000");
        replaceVolume(rows, size - 3, "100000");
        replaceVolume(rows, size - 2, "200000");
        replaceVolume(rows, size - 1, "300000");
        LocalDate scanTradeDate = rows.get(size - 1).tradeDate().plusDays(1);

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(size - 1).close(),
                scanTradeDate,
                new BigDecimal("150000"),
                false,
                ruleSet
        );

        assertThat(result.snapshot().todayVolume()).isEqualByComparingTo("150000.00");
        assertThat(result.snapshot().averageVolume3()).isEqualByComparingTo("200000.00");
        assertThat(result.snapshot().volumeRatio3()).isEqualByComparingTo("0.75");
    }

    @Test
    void marksThreeDayVolumeComparisonUnavailableWhenARequiredVolumeIsMissing() {
        List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600206"));
        int size = rows.size();
        replaceVolume(rows, size - 4, "100000");
        replaceVolume(rows, size - 3, null);
        replaceVolume(rows, size - 2, "300000");
        replaceVolume(rows, size - 1, "300000");

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(rows.size() - 1).close(),
                false,
                ruleSet
        );

        assertThat(result.snapshot().todayVolume()).isNull();
        assertThat(result.snapshot().averageVolume3()).isNull();
        assertThat(result.snapshot().volumeRatio3()).isNull();
    }

    @Test
    void marksThreeDayVolumeComparisonUnavailableForZeroOrNegativeRequiredVolume() {
        for (String invalidVolume : List.of("0", "-1")) {
            List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600207"));
            int size = rows.size();
            replaceVolume(rows, size - 4, "100000");
            replaceVolume(rows, size - 3, invalidVolume);
            replaceVolume(rows, size - 2, "300000");
            replaceVolume(rows, size - 1, "300000");

            ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                    rows,
                    rows.get(rows.size() - 1).close(),
                    false,
                    ruleSet
            );

            assertThat(result.snapshot().todayVolume()).isNull();
            assertThat(result.snapshot().averageVolume3()).isNull();
            assertThat(result.snapshot().volumeRatio3()).isNull();
        }
    }

    private List<EastMoneyKLine> confirmedReplayRows(String symbol) {
        List<BigDecimal> closes = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            closes.add(new BigDecimal(index % 2 == 0 ? "10.02" : "9.98"));
        }
        closes.addAll(Collections.nCopies(5, new BigDecimal("9.80")));
        closes.addAll(Collections.nCopies(4, new BigDecimal("9.70")));
        closes.add(new BigDecimal("10.30"));
        closes.add(new BigDecimal("10.42"));
        return rows(symbol, closes);
    }

    private List<EastMoneyKLine> formingReplayRows(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows(symbol));
        int completedIndex = rows.size() - 2;
        rows.set(completedIndex, row(symbol, rows.get(completedIndex).tradeDate(), new BigDecimal("9.70"), "100000"));
        return rows;
    }

    private List<EastMoneyKLine> establishedReplayRows(String symbol) {
        List<EastMoneyKLine> confirmed = confirmedReplayRows(symbol);
        List<BigDecimal> closes = confirmed.stream()
                .limit(confirmed.size() - 1L)
                .map(EastMoneyKLine::close)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        closes.addAll(List.of(
                new BigDecimal("10.34"),
                new BigDecimal("10.38"),
                new BigDecimal("10.42"),
                new BigDecimal("10.46"),
                new BigDecimal("10.48")
        ));
        return rows(symbol, closes);
    }

    private List<EastMoneyKLine> noneReplayRows(String symbol) {
        List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(20, new BigDecimal("10.00")));
        closes.addAll(Collections.nCopies(6, new BigDecimal("9.00")));
        return rows(symbol, closes);
    }

    private List<EastMoneyKLine> rows(String symbol, List<BigDecimal> closes) {
        LocalDate start = LocalDate.parse("2025-01-01");
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int index = 0; index < closes.size(); index++) {
            String volume = index == closes.size() - 1 ? "190000" : "100000";
            rows.add(row(symbol, start.plusDays(index), closes.get(index), volume));
        }
        return rows;
    }

    private EastMoneyKLine row(String symbol, LocalDate date, BigDecimal close, String volume) {
        return new EastMoneyKLine(
                symbol,
                date,
                close.subtract(new BigDecimal("0.03")),
                close,
                close.add(new BigDecimal("0.07")),
                close.subtract(new BigDecimal("0.08")),
                new BigDecimal(volume),
                null
        );
    }

    private void replaceVolume(List<EastMoneyKLine> rows, int index, String volume) {
        EastMoneyKLine existing = rows.get(index);
        rows.set(index, new EastMoneyKLine(
                existing.symbol(),
                existing.tradeDate(),
                existing.open(),
                existing.close(),
                existing.high(),
                existing.low(),
                volume == null ? null : new BigDecimal(volume),
                existing.amount(),
                existing.turnoverRate()
        ));
    }
}

package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermGoldenCrossAnalyzerTest {

    private final ShortTermGoldenCrossAnalyzer analyzer = new ShortTermGoldenCrossAnalyzer();

    @Test
    void confirmsLatestCompletedMa5OverMa10Cross() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(0), true);

        assertThat(result.state()).isEqualTo("CONFIRMED");
        assertThat(result.tradingDaysSinceCross()).isZero();
        assertThat(result.priorityTier()).isEqualTo(3);
        assertThat(result.ma5Ma10SpreadPercent()).isPositive();
    }

    @Test
    void keepsCrossRecentThroughThreeCompletedBars() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(3), true);

        assertThat(result.state()).isEqualTo("CONFIRMED");
        assertThat(result.tradingDaysSinceCross()).isEqualTo(3);
    }

    @Test
    void classifiesOlderCrossAsEstablished() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(4), true);

        assertThat(result.state()).isEqualTo("ESTABLISHED");
        assertThat(result.priorityTier()).isEqualTo(1);
    }

    @Test
    void detectsNarrowingApproachWithoutCallingItConfirmed() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(approachingRows(), true);

        assertThat(result.state()).isEqualTo("APPROACHING");
        assertThat(result.spreadTrend()).isEqualTo("NARROWING");
        assertThat(result.ma5Ma10SpreadPercent())
                .isBetween(new BigDecimal("-0.80"), BigDecimal.ZERO);
    }

    @Test
    void marksCurrentUnfinishedBarCrossAsForming() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(0), false);

        assertThat(result.state()).isEqualTo("FORMING");
        assertThat(result.priorityTier()).isEqualTo(2);
    }

    @Test
    void returnsUnavailableWhenTwentyBarsAreNotPresent() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(rows(List.of("10", "10.1", "10.2")), true);

        assertThat(result.state()).isEqualTo("UNAVAILABLE");
        assertThat(result.evidenceStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void returnsUnavailableForNullOrIncompleteRows() {
        List<EastMoneyKLine> rowsWithNullRow = new ArrayList<>(crossRows(0));
        rowsWithNullRow.set(4, null);
        List<EastMoneyKLine> rowsWithMissingDate = new ArrayList<>(crossRows(0));
        rowsWithMissingDate.set(4, kLine(null, new BigDecimal("10.00")));
        List<EastMoneyKLine> rowsWithMissingClose = new ArrayList<>(crossRows(0));
        rowsWithMissingClose.set(4, kLine(LocalDate.parse("2026-01-05"), null));

        assertThat(analyzer.analyze(rowsWithNullRow, true).state()).isEqualTo("UNAVAILABLE");
        assertThat(analyzer.analyze(rowsWithMissingDate, true).state()).isEqualTo("UNAVAILABLE");
        assertThat(analyzer.analyze(rowsWithMissingClose, true).state()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void returnsUnavailableForDuplicateTradeDates() {
        List<EastMoneyKLine> rowsWithDuplicateDate = new ArrayList<>(crossRows(0));
        EastMoneyKLine duplicateDate = rowsWithDuplicateDate.get(4);
        rowsWithDuplicateDate.set(5, kLine(duplicateDate.tradeDate(), new BigDecimal("10.00")));

        assertThat(analyzer.analyze(rowsWithDuplicateDate, true).state()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void ignoresUnfinishedBarMetricsWhenTheCompletedCrossRemainsConfirmed() {
        List<EastMoneyKLine> completedRows = crossRows(0);
        List<EastMoneyKLine> rowsWithUnfinishedBar = new ArrayList<>(completedRows);
        rowsWithUnfinishedBar.add(kLine(LocalDate.parse("2026-01-22"), new BigDecimal("1.00")));

        ShortTermGoldenCrossSnapshot completed = analyzer.analyze(completedRows, true);
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(rowsWithUnfinishedBar, false);

        assertThat(result.state()).isEqualTo("CONFIRMED");
        assertThat(result.evidenceStatus()).isEqualTo("COMPLETE");
        assertThat(result.ma5Ma10SpreadPercent()).isEqualByComparingTo(completed.ma5Ma10SpreadPercent());
        assertThat(result.maAlignment()).isEqualTo(completed.maAlignment());
    }

    private List<EastMoneyKLine> crossRows(int barsAfterCross) {
        List<String> closes = new ArrayList<>(Collections.nCopies(20, "10.00"));
        closes.add("10.50");
        for (int index = 0; index < barsAfterCross; index++) {
            closes.add(new BigDecimal("10.55")
                    .add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(index))).toPlainString());
        }
        return rows(closes);
    }

    private List<EastMoneyKLine> approachingRows() {
        List<String> closes = new ArrayList<>(Collections.nCopies(15, "10.50"));
        closes.addAll(Collections.nCopies(5, "10.00"));
        closes.addAll(List.of("10.10", "10.20", "10.30"));
        return rows(closes);
    }

    private List<EastMoneyKLine> rows(List<String> closes) {
        LocalDate start = LocalDate.parse("2026-01-01");
        return IntStream.range(0, closes.size())
                .mapToObj(index -> kLine(start.plusDays(index), new BigDecimal(closes.get(index))))
                .toList();
    }

    private EastMoneyKLine kLine(LocalDate tradeDate, BigDecimal close) {
        return new EastMoneyKLine("600001", tradeDate, close, close, close, close,
                new BigDecimal("100000"), null);
    }
}

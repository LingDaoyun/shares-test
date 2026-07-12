package com.aistock.research.integration.eastmoney;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KLineSeriesIntegrityTest {

    private static final LocalDate BEGIN = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Test
    void acceptsFullyParsedUniqueRowsWithBoundaryCoverage() {
        KLineSeriesIntegrity.Result result = KLineSeriesIntegrity.assess(
                List.of(bar(BEGIN.plusDays(1)), bar(END.minusDays(1))),
                2, 2, 0, 1, 1, BEGIN, END);

        assertThat(result.complete()).isTrue();
        assertThat(result.detail()).contains("2/2");
    }

    @Test
    void rejectsEmptySlicesParsingLossDuplicatesAndTruncatedBoundaries() {
        assertThat(KLineSeriesIntegrity.assess(
                List.of(bar(BEGIN.plusDays(1))),
                1, 1, 0, 2, 1, BEGIN, END).complete()).isFalse();
        assertThat(KLineSeriesIntegrity.assess(
                List.of(bar(BEGIN.plusDays(1))),
                2, 1, 0, 1, 1, BEGIN, END).complete()).isFalse();
        assertThat(KLineSeriesIntegrity.assess(
                List.of(bar(BEGIN.plusDays(1))),
                2, 2, 1, 1, 1, BEGIN, END).complete()).isFalse();
        assertThat(KLineSeriesIntegrity.assess(
                List.of(bar(BEGIN.plusDays(15)), bar(END.minusDays(1))),
                2, 2, 0, 1, 1, BEGIN, END).complete()).isFalse();
        assertThat(KLineSeriesIntegrity.assess(
                List.of(bar(BEGIN.plusDays(1)), bar(END.minusDays(15))),
                2, 2, 0, 1, 1, BEGIN, END).complete()).isFalse();
        assertThat(KLineSeriesIntegrity.assess(
                List.of(new EastMoneyKLine(
                        "600519", BEGIN.plusDays(1), BigDecimal.ONE, null,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)),
                1, 1, 0, 1, 1, BEGIN, BEGIN.plusDays(2)).complete()).isFalse();
    }

    @Test
    void rejectsStructurallyImpossibleOhlcRows() {
        EastMoneyKLine highBelowBody = new EastMoneyKLine(
                "600519", BEGIN.plusDays(1), new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("9"), new BigDecimal("8"), BigDecimal.ONE, BigDecimal.ONE);
        EastMoneyKLine lowAboveBody = new EastMoneyKLine(
                "600519", BEGIN.plusDays(1), new BigDecimal("10"), new BigDecimal("9"),
                new BigDecimal("12"), new BigDecimal("11"), BigDecimal.ONE, BigDecimal.ONE);

        assertThat(KLineSeriesIntegrity.assess(
                List.of(highBelowBody), 1, 1, 0, 1, 1,
                BEGIN, BEGIN.plusDays(2)).complete()).isFalse();
        assertThat(KLineSeriesIntegrity.assess(
                List.of(lowAboveBody), 1, 1, 0, 1, 1,
                BEGIN, BEGIN.plusDays(2)).complete()).isFalse();
    }

    @Test
    void rejectsMultiYearSeriesWhenAnyAnnualSliceIsIncomplete() {
        KLineSeriesIntegrity.Result result = KLineSeriesIntegrity.assess(
                List.of(bar(BEGIN.plusDays(1)), bar(END.minusDays(1))),
                2, 2, 0, 2, 1, BEGIN, END);

        assertThat(result.complete()).isFalse();
        assertThat(result.detail()).contains("分片 1/2");
    }

    private EastMoneyKLine bar(LocalDate tradeDate) {
        return new EastMoneyKLine(
                "600519", tradeDate, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }
}

package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortTermMarketLiquidityServiceTest {
    private static final LocalDate DAY = LocalDate.parse("2026-10-16");
    private static final LocalDate PREVIOUS = LocalDate.parse("2026-10-15");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final EastMoneyClient client = mock(EastMoneyClient.class);
    private final ShortTermMarketLiquidityService service = new ShortTermMarketLiquidityService(client, new TradingClockService());

    @Test
    void comparesOnlyCompletedMinutesOnBothDatesAndKeepsSampleAmountSeparate() {
        stub(series(LocalTime.of(15, 0)), series(LocalTime.of(15, 0)));
        var result = snapshot(DAY.atTime(9, 32, 30));
        assertThat(result.status()).isEqualTo("SAME_TIME");
        assertThat(result.comparisonTime()).isEqualTo("09:31");
        assertThat(result.currentComparableAmountYuan()).isEqualByComparingTo("800");
        assertThat(result.previousComparableAmountYuan()).isEqualByComparingTo("400");
        assertThat(result.amountChangePercent()).isEqualByComparingTo("100");
        assertThat(result.volumeChangePercent()).isEqualByComparingTo("-50");
        assertThat(result.sampleAmountYuan()).isEqualByComparingTo("1000");
        assertThat(result.comparisonDate()).isEqualTo(PREVIOUS);
    }

    @Test
    void missingPriorMinuteRejectsApparentlyLargeExpansion() {
        var incomplete = series(LocalTime.of(10, 0));
        incomplete.removeIf(p -> p.minute().equals(PREVIOUS.atTime(9, 45)));
        stub(incomplete, series(LocalTime.of(10, 0)));
        assertUnavailable(snapshot(DAY.atTime(10, 1)), "分钟");
    }

    @Test
    void anAfternoonSliceCannotPretendToBeFullDayTurnover() {
        var incomplete = series(LocalTime.of(15, 0));
        incomplete.removeIf(p -> p.minute().toLocalTime().isBefore(LocalTime.of(13, 0)));
        stub(incomplete, series(LocalTime.of(15, 0)));
        assertUnavailable(snapshot(DAY.atTime(15, 1)), "分钟");
    }

    @Test
    void currentMinuteIsNotAClosedSessionEvenAt1500() {
        stub(series(LocalTime.of(15, 0)), series(LocalTime.of(15, 0)));
        assertThat(snapshot(DAY.atTime(15, 0)).status()).isEqualTo("SAME_TIME");
        var closed = snapshot(DAY.atTime(15, 1));
        assertThat(closed.status()).isEqualTo("CLOSE");
        assertThat(closed.comparisonTime()).isEqualTo("15:00");
        assertThat(closed.currentComparableAmountYuan()).isEqualByComparingTo("96400");
    }

    @Test
    void lunchComparesMorningCloseWithoutCallingItStale() {
        stub(series(LocalTime.of(11, 30)), series(LocalTime.of(11, 30)));
        var result = snapshot(DAY.atTime(12, 15));
        assertThat(result.status()).isEqualTo("SAME_TIME");
        assertThat(result.comparisonTime()).isEqualTo("11:30");
    }

    @Test
    void staleCurrentMinutesCannotBecomeAnExecutionComparison() {
        stub(series(LocalTime.of(9, 45)), series(LocalTime.of(9, 45)));
        assertUnavailable(snapshot(DAY.atTime(10, 1)), "滞后");
    }

    @Test
    void threeMinutesMeansActualSourceAgeRatherThanThreeAdditionalCompletedMinutes() {
        stub(series(LocalTime.of(9, 57)), series(LocalTime.of(9, 57)));
        assertUnavailable(snapshot(DAY.atTime(10, 1)), "滞后");
    }

    @Test
    void beijingQuotesDoNotEnterShanghaiShenzhenAmountOrItsCoverageDenominator() {
        stub(series(LocalTime.of(10, 0)), series(LocalTime.of(10, 0)));
        var decision = DAY.atTime(10, 1);
        var sh = quote(decision, "1000");
        var beijing = new EastMoneyQuote("920001", "北交样本", "BJ", "行业", BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, null, null, null, null,
                "fixture", "", sh.fetchedAt(), sh.tradeDate(), sh.marketTimestamp());
        var result = service.snapshot(List.of(sh, beijing), coverage(), decision);
        assertThat(result.sampleAmountYuan()).isEqualByComparingTo("1000");
        assertThat(result.amountCoveragePercent()).isEqualByComparingTo("100");
    }

    @Test
    void zeroPriorAmountCannotInventAnExpansionPercentage() {
        var zeroBaseline = series(LocalTime.of(10, 0)).stream()
                .map(p -> p.minute().toLocalDate().equals(PREVIOUS) ? point(p.minute(), "0", "0") : p).toList();
        stub(zeroBaseline, zeroBaseline);
        assertUnavailable(snapshot(DAY.atTime(10, 1)), "为0");
    }

    @Test
    void olderBaselineIsNotSubstitutedForVerifiedPreviousTradingDay() {
        var wrongDate = series(LocalTime.of(10, 0)).stream()
                .map(p -> p.minute().toLocalDate().equals(PREVIOUS)
                        ? point(p.minute().minusDays(1), "100", "20") : p).toList();
        stub(wrongDate, series(LocalTime.of(10, 0)));
        assertUnavailable(snapshot(DAY.atTime(10, 1)), "前一交易日");
    }

    @Test
    void conflictingDuplicateCannotInflateTotals() {
        var duplicate = series(LocalTime.of(10, 0));
        duplicate.add(point(DAY.atTime(9, 31), "999999", "10"));
        stub(duplicate, series(LocalTime.of(10, 0)));
        assertUnavailable(snapshot(DAY.atTime(10, 1)), "冲突");
    }

    @Test
    void negativeMinuteIsInvalidButZeroAuctionMinutesAreValid() {
        var invalid = series(LocalTime.of(10, 0));
        invalid.removeIf(p -> p.minute().equals(DAY.atTime(9, 31)));
        invalid.add(point(DAY.atTime(9, 31), "-1", "0"));
        stub(invalid, series(LocalTime.of(10, 0)));
        assertUnavailable(snapshot(DAY.atTime(10, 1)), "负值");
        invalid.removeIf(p -> p.minute().equals(DAY.atTime(9, 31)));
        invalid.add(point(DAY.atTime(9, 31), "0", "0"));
        assertThat(snapshot(DAY.atTime(10, 1)).status()).isEqualTo("SAME_TIME");
    }

    @Test
    void missingOrFutureQuoteAmountsAreNotPresentedAsZero() {
        stub(series(LocalTime.of(10, 0)), series(LocalTime.of(10, 0)));
        var decision = DAY.atTime(10, 1);
        var missing = service.snapshot(List.of(quote(decision, null)), coverage(), decision);
        assertThat(missing.sampleAmountYuan()).isNull();
        assertThat(missing.amountCoveragePercent()).isEqualByComparingTo("0");
        var future = service.snapshot(List.of(quote(decision.plusMinutes(1), "1000")), coverage(), decision);
        assertThat(future.sampleAmountYuan()).isNull();
        assertThat(future.status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void zeroQuoteAmountHasValidCoverageWithoutInventingMoney() {
        stub(series(LocalTime.of(10, 0)), series(LocalTime.of(10, 0)));
        var decision = DAY.atTime(10, 1);
        var result = service.snapshot(List.of(quote(decision, "0")), coverage(), decision);
        assertThat(result.sampleAmountYuan()).isEqualByComparingTo("0");
        assertThat(result.amountCoveragePercent()).isEqualByComparingTo("100");
    }

    @Test
    void weekendCanShowLastClosedQuoteDayButNeverRelabelItAsWeekend() {
        stub(series(LocalTime.of(15, 0)), series(LocalTime.of(15, 0)));
        var result = service.snapshot(List.of(quote(DAY.atTime(15, 0), "1000")), coverage(), DAY.plusDays(1).atTime(10, 1));
        assertThat(result.status()).isEqualTo("CLOSE");
        assertThat(result.tradeDate()).isEqualTo(DAY);
        assertThat(result.comparisonDate()).isEqualTo(PREVIOUS);
    }

    @Test
    void priorQuoteDayDuringOpenMarketIsNotCurrentLiquidity() {
        stub(series(LocalTime.of(15, 0)), series(LocalTime.of(15, 0)));
        var result = service.snapshot(List.of(quote(PREVIOUS.atTime(15, 0), "1000")), coverage(), DAY.atTime(10, 1));
        assertUnavailable(result, "行情日");
    }

    private ShortTermMarketLiquidity snapshot(LocalDateTime decision) {
        return service.snapshot(List.of(quote(decision, "1000")), coverage(), decision);
    }

    private void assertUnavailable(ShortTermMarketLiquidity result, String reason) {
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.amountChangePercent()).isNull();
        assertThat(result.volumeChangePercent()).isNull();
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains(reason));
    }

    private void stub(List<EastMoneyIntradayPoint> sh, List<EastMoneyIntradayPoint> sz) {
        when(client.fetchIndexIntradayTrends("sh000001")).thenReturn(sh);
        when(client.fetchIndexIntradayTrends("sz399106")).thenReturn(sz);
    }

    private List<EastMoneyIntradayPoint> series(LocalTime end) {
        var points = new ArrayList<EastMoneyIntradayPoint>();
        for (var day : List.of(PREVIOUS, DAY)) {
            for (var minute = LocalTime.of(9, 30); !minute.isAfter(end); minute = minute.plusMinutes(1)) {
                if (minute.isAfter(LocalTime.of(11, 30)) && minute.isBefore(LocalTime.of(13, 1))) continue;
                points.add(point(day.atTime(minute), day.equals(DAY) ? "200" : "100", day.equals(DAY) ? "10" : "20"));
            }
        }
        return points;
    }

    private EastMoneyIntradayPoint point(LocalDateTime minute, String amount, String volume) {
        return new EastMoneyIntradayPoint("index", minute, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal(volume), new BigDecimal(amount), BigDecimal.ONE);
    }

    private EastMoneyQuote quote(LocalDateTime time, String amount) {
        Instant at = time.atZone(SHANGHAI).toInstant();
        return new EastMoneyQuote("600000", "样本", "SH", "银行", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.TEN, amount == null ? null : new BigDecimal(amount), null, null, null, "fixture", "", at,
                time.toLocalDate(), at);
    }

    private ShortTermCoverageSnapshot coverage() {
        return new ShortTermCoverageSnapshot(1, 1, 0, BigDecimal.ONE, true, "fixture", Instant.EPOCH);
    }
}

package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyKLineSeries;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ShortTermStockChartControllerTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-10-26T06:00:00Z");

    @Test
    void keepsOnlyLatestActualSessionAndSortsWithoutAddingDuplicateVolume() {
        var result = ShortTermStockChartController.intradaySnapshot("600000", List.of(
                point("600000", "2026-10-23T14:59", "10", "100"),
                point("600000", "2026-10-26T13:02", "11", "200"),
                point("600000", "2026-10-26T09:30", "10.5", "50"),
                point("600000", "2026-10-26T13:02", "11", "200")
        ), FETCHED_AT, "东方财富");

        assertThat(result.tradeDate()).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(result.source()).isEqualTo("东方财富");
        assertThat(result.volumeUnit()).isEqualTo("手");
        assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(0).minute()).isEqualTo(LocalDateTime.parse("2026-10-26T09:30"));
        assertThat(result.points().get(1).volume()).isEqualByComparingTo("200");
    }

    @Test
    void rejectsOtherSymbolsInvalidPricesNonTradingTimesAndFuturePoints() {
        var result = ShortTermStockChartController.intradaySnapshot("600000", Arrays.asList(
                null,
                point("600001", "2026-10-26T09:30", "10", "100"),
                point("600000", "2026-10-26T09:30", "0", "100"),
                point("600000", "2026-10-26T09:31", null, "100"),
                point("600000", "2026-10-26T09:25", "10", "100"),
                point("600000", "2026-10-26T12:00", "10", "100"),
                point("600000", "2026-10-26T15:01", "10", "100"),
                point("600000", "2026-10-26T14:01", "10", "100"),
                point("600000", "2026-10-27T09:30", "10", "100")
        ), FETCHED_AT, "东方财富");

        assertThat(result.points()).isEmpty();
        assertThat(result.tradeDate()).isNull();
    }

    @Test
    void retainsActualHistoricalDateAndLeavesInvalidMetricsMissing() {
        var point = new EastMoneyIntradayPoint("600000", LocalDateTime.parse("2026-10-23T15:00"),
                null, new BigDecimal("10"), null, null,
                new BigDecimal("-1"), new BigDecimal("-2"), BigDecimal.ZERO);
        var result = ShortTermStockChartController.intradaySnapshot("600000", List.of(point), FETCHED_AT, "东方财富");

        assertThat(result.tradeDate()).isEqualTo(LocalDate.parse("2026-10-23"));
        assertThat(result.points().get(0).volume()).isNull();
        assertThat(result.points().get(0).amount()).isNull();
        assertThat(result.points().get(0).averagePrice()).isNull();
    }

    @Test
    void fallsBackToSinaWhenEastMoneyTransportFails() {
        var client = mock(EastMoneyClient.class);
        var controller = new ShortTermStockChartController(client);
        when(client.fetchIntradayOhlcv("600156")).thenThrow(new IllegalStateException("东方财富连接中断"));
        when(client.fetchSinaIntradayTrends("600156")).thenReturn(List.of(
                point("600156", "2026-10-22T15:00", "8.09", "97"),
                point("600156", "2026-10-23T15:00", "8.90", "1024")
        ));

        var result = controller.intraday("600156");

        assertThat(result.source()).isEqualTo("新浪");
        assertThat(result.tradeDate()).isEqualTo(LocalDate.parse("2026-10-23"));
        assertThat(result.volumeUnit()).isEqualTo("手");
        assertThat(result.points()).singleElement().satisfies(point ->
                assertThat(point.volume()).isEqualByComparingTo("1024"));
    }

    @Test
    void preservesEmptyPrimarySnapshotWhenSinaFails() {
        var client = mock(EastMoneyClient.class);
        when(client.fetchIntradayOhlcv("600156")).thenReturn(List.of());
        when(client.fetchSinaIntradayTrends("600156")).thenThrow(new IllegalStateException("新浪连接中断"));

        var result = new ShortTermStockChartController(client).intraday("600156");

        assertThat(result.source()).isEqualTo("东方财富");
        assertThat(result.points()).isEmpty();
    }

    @Test
    void rejectsInvalidSymbolBeforeRequestingMarketData() {
        var client = mock(EastMoneyClient.class);
        var controller = new ShortTermStockChartController(client);

        assertThatThrownBy(() -> controller.intraday("sh600000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.intraday("60000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.daily("sh600000")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void dailyUsesOnlyCompleteSeriesAndRetainsActualLastDateAndVolumeUnit() {
        List<EastMoneyKLine> rows = IntStream.range(0, 130)
                .mapToObj(index -> dailyBar("600000", LocalDate.parse("2026-10-23").minusDays(index)))
                .toList();
        var result = ShortTermStockChartController.dailySnapshot("600000",
                new EastMoneyKLineSeries(rows, "TENCENT_QFQ_DAILY", true, "完整"), FETCHED_AT);

        assertThat(result.adjustMode()).isEqualTo("QFQ");
        assertThat(result.volumeUnit()).isEqualTo("手");
        assertThat(result.source()).isEqualTo("腾讯");
        assertThat(result.bars()).hasSize(120);
        assertThat(result.bars().get(119).tradeDate()).isEqualTo(LocalDate.parse("2026-10-23"));
        assertThat(result.bars().get(119).volume()).isEqualByComparingTo("123");
        assertThat(result.bars().get(0).tradeDate()).isBefore(result.bars().get(119).tradeDate());
    }

    @Test
    void dailyRejectsIncompleteOrMismatchedDataInsteadOfShowingItAsComplete() {
        var validBar = dailyBar("600000", LocalDate.parse("2026-10-26"));
        assertThatThrownBy(() -> ShortTermStockChartController.dailySnapshot("600000",
                new EastMoneyKLineSeries(List.of(validBar), "HISTORICAL_KLINE_PARTIAL", false, "起始缺口"),
                FETCHED_AT)).isInstanceOf(IllegalStateException.class).hasMessageContaining("起始缺口");
        assertThatThrownBy(() -> ShortTermStockChartController.dailySnapshot("600000",
                new EastMoneyKLineSeries(List.of(dailyBar("600001", LocalDate.parse("2026-10-26"))),
                        "TENCENT_QFQ_DAILY", true, "完整"), FETCHED_AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ShortTermStockChartController.dailySnapshot("600000",
                new EastMoneyKLineSeries(List.of(dailyBar("600000", LocalDate.parse("2026-10-27"))),
                        "TENCENT_QFQ_DAILY", true, "完整"), FETCHED_AT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dailyAllowsAnUnclosedCurrentDayBarFromACompleteSource() {
        var result = ShortTermStockChartController.dailySnapshot("600000",
                new EastMoneyKLineSeries(List.of(dailyBar("600000", LocalDate.parse("2026-10-26"))),
                        "EAST_MONEY_QFQ_DAILY", true, "完整"), FETCHED_AT);
        assertThat(result.bars()).hasSize(1);
        assertThat(result.source()).isEqualTo("东方财富");
        assertThat(result.bars().get(0).tradeDate()).isEqualTo(LocalDate.parse("2026-10-26"));
    }

    @Test
    void dailyPreservesMissingVolumeAndUsesReadableSourceNames() {
        var row = new EastMoneyKLine("600000", LocalDate.parse("2026-10-23"),
                new BigDecimal("10"), new BigDecimal("11"), new BigDecimal("12"), new BigDecimal("9"),
                null, null);
        var result = ShortTermStockChartController.dailySnapshot("600000",
                new EastMoneyKLineSeries(List.of(row), "SINA_QFQ_DAILY", true, "完整"), FETCHED_AT);

        assertThat(result.bars().get(0).volume()).isNull();
        assertThat(result.source()).isEqualTo("新浪");
        assertThat(ShortTermStockChartController.dailySnapshot("600000",
                new EastMoneyKLineSeries(List.of(row), "UNRECOGNIZED_INTERNAL_SOURCE", true, "完整"),
                FETCHED_AT).source()).isEqualTo("日线行情源");
    }

    @Test
    void requeriesRecentWindowForA2026ListingWith130Bars() {
        assertListingWindowRequeried(130);
    }

    @Test
    void requeriesOnlyAvailableWindowForAListingWithFiveBars() {
        assertListingWindowRequeried(5);
    }

    @Test
    void narrowedWindowStillMustPassSourceCompletenessChecks() {
        var today = LocalDate.parse("2026-10-26");
        var client = mock(EastMoneyClient.class);
        var controller = new ShortTermStockChartController(client);
        var partial = new EastMoneyKLineSeries(List.of(dailyBar("600000", today.minusDays(3))),
                "HISTORICAL_KLINE_PARTIAL", false, "来源数据仍不完整");
        when(client.fetchDailyKLineSeries("600000", today.minusYears(1), today)).thenReturn(partial);
        when(client.fetchDailyKLineSeries("600000", today.minusDays(3), today)).thenReturn(partial);

        assertThatThrownBy(() -> ShortTermStockChartController.dailySnapshot("600000",
                controller.fetchDailyChartSeries("600000", today), FETCHED_AT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("来源数据仍不完整");
        verify(client).fetchDailyKLineSeries("600000", today.minusYears(1), today);
        verify(client).fetchDailyKLineSeries("600000", today.minusDays(3), today);
        verifyNoMoreInteractions(client);
    }

    @Test
    void sourceExceptionsDoNotTriggerASecondRequest() {
        var today = LocalDate.parse("2026-10-26");
        var client = mock(EastMoneyClient.class);
        var controller = new ShortTermStockChartController(client);
        when(client.fetchDailyKLineSeries("600000", today.minusYears(1), today))
                .thenThrow(new IllegalStateException("来源访问失败"));

        assertThatThrownBy(() -> controller.fetchDailyChartSeries("600000", today))
                .isInstanceOf(IllegalStateException.class).hasMessage("来源访问失败");
        verify(client).fetchDailyKLineSeries("600000", today.minusYears(1), today);
        verifyNoMoreInteractions(client);
    }

    @Test
    void partialSeriesWithoutUsableDatesDoesNotTriggerASecondRequest() {
        var today = LocalDate.parse("2026-10-26");
        var client = mock(EastMoneyClient.class);
        var controller = new ShortTermStockChartController(client);
        var partial = new EastMoneyKLineSeries(List.of(dailyBar("600001", today),
                dailyBar("600000", today.plusDays(1)), dailyBar("600000", null)),
                "HISTORICAL_KLINE_PARTIAL", false, "日期不可用");
        when(client.fetchDailyKLineSeries("600000", today.minusYears(1), today)).thenReturn(partial);

        assertThat(controller.fetchDailyChartSeries("600000", today)).isSameAs(partial);
        verify(client).fetchDailyKLineSeries("600000", today.minusYears(1), today);
        verifyNoMoreInteractions(client);
    }

    private static void assertListingWindowRequeried(int count) {
        var today = LocalDate.parse("2026-10-26");
        List<EastMoneyKLine> rows = IntStream.range(0, count)
                .mapToObj(index -> dailyBar("600000", today.minusDays(3 + count - 1 - index)))
                .toList();
        var rawRows = new ArrayList<>(rows);
        rawRows.addAll(rows);
        rawRows.add(dailyBar("600001", today));
        rawRows.add(dailyBar("600000", today.plusDays(1)));
        var partial = new EastMoneyKLineSeries(rawRows, "HISTORICAL_KLINE_PARTIAL", false, "起始边界缺口");
        var recentRows = rows.subList(Math.max(0, count - 120), count);
        var complete = new EastMoneyKLineSeries(recentRows, "TENCENT_QFQ_DAILY", true, "请求范围完整");
        var windowStart = recentRows.get(0).tradeDate();
        var client = mock(EastMoneyClient.class);
        var controller = new ShortTermStockChartController(client);
        when(client.fetchDailyKLineSeries("600000", today.minusYears(1), today)).thenReturn(partial);
        when(client.fetchDailyKLineSeries("600000", windowStart, today)).thenReturn(complete);

        var result = controller.fetchDailyChartSeries("600000", today);

        assertThat(result).isSameAs(complete);
        assertThat(ShortTermStockChartController.dailySnapshot("600000", result, FETCHED_AT).bars())
                .hasSize(Math.min(120, count));
        verify(client).fetchDailyKLineSeries("600000", today.minusYears(1), today);
        verify(client).fetchDailyKLineSeries("600000", windowStart, today);
        verifyNoMoreInteractions(client);
    }

    private static EastMoneyIntradayPoint point(String symbol, String minute, String price, String volume) {
        return new EastMoneyIntradayPoint(symbol, LocalDateTime.parse(minute), null,
                price == null ? null : new BigDecimal(price), null, null,
                new BigDecimal(volume), null, null);
    }

    private static EastMoneyKLine dailyBar(String symbol, LocalDate tradeDate) {
        return new EastMoneyKLine(symbol, tradeDate, new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("12"), new BigDecimal("9"), new BigDecimal("123"), null);
    }
}

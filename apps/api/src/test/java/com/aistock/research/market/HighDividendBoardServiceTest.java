package com.aistock.research.market;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.universe.UniversalAshareScreener;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HighDividendBoardServiceTest {

    private static final int WHOLE_MARKET_LIMIT = 6000;

    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final HighDividendBoardService service = new HighDividendBoardService(eastMoneyClient);
    private final int latestFullYear = UniversalAshareScreener.latestCompletedAnnualReportYear(LocalDate.now());

    @Test
    void fetchSnapshotFiltersByThresholdRanksAndEnrichesQuotes() {
        Map<String, EastMoneyAnnualIndicator> indicators = new LinkedHashMap<>();
        indicators.put("600001", indicator("600001", "甲银行", "0.081"));
        indicators.put("600002", indicator("600002", "乙煤炭", "0.062"));
        indicators.put("600003", indicator("600003", "丙交通", "0.05"));
        indicators.put("600004", indicator("600004", "丁制造", "0.031"));
        indicators.put("600005", indicator("600005", "戊无分红", null));
        when(eastMoneyClient.fetchAnnualIndicators(latestFullYear, WHOLE_MARKET_LIMIT)).thenReturn(indicators);
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(anyList(), anyInt())).thenReturn(List.of(
                quote("600001", "甲银行", "银行", "10.50", "1.20", "6.20", "0.65"),
                quote("600002", "乙煤炭", "煤炭", "12.00", "-0.50", "8.10", "1.30")
        ));

        HighDividendBoardSnapshot snapshot = service.fetchSnapshot(new BigDecimal("0.05"));

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.reportYear()).isEqualTo(latestFullYear);
        assertThat(snapshot.thresholdYield()).isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(snapshot.totalCount()).isEqualTo(2);
        assertThat(snapshot.dataGaps()).isEmpty();
        assertThat(snapshot.stocks()).hasSize(2);
        HighDividendStock first = snapshot.stocks().get(0);
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.symbol()).isEqualTo("600001");
        assertThat(first.industry()).isEqualTo("银行");
        assertThat(first.dividendYield()).isEqualByComparingTo(new BigDecimal("0.081"));
        assertThat(first.payoutRatio()).isEqualByComparingTo(new BigDecimal("0.1563"));
        assertThat(first.latestPrice()).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(first.peTtm()).isEqualByComparingTo(new BigDecimal("6.20"));
        assertThat(snapshot.stocks().get(1).symbol()).isEqualTo("600002");
        assertThat(snapshot.industryStats()).extracting(HighDividendIndustryStat::industry)
                .containsExactly("银行", "煤炭");
    }

    @Test
    void fetchSnapshotKeepsRankingWhenQuoteEnrichmentFails() {
        Map<String, EastMoneyAnnualIndicator> indicators = new LinkedHashMap<>();
        indicators.put("600001", indicator("600001", "甲银行", "0.081"));
        when(eastMoneyClient.fetchAnnualIndicators(latestFullYear, WHOLE_MARKET_LIMIT)).thenReturn(indicators);
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(anyList(), anyInt()))
                .thenThrow(new IllegalStateException("行情获取失败"));

        HighDividendBoardSnapshot snapshot = service.fetchSnapshot(new BigDecimal("0.05"));

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.stocks()).hasSize(1);
        HighDividendStock stock = snapshot.stocks().get(0);
        assertThat(stock.name()).isEqualTo("甲银行");
        assertThat(stock.industry()).isEqualTo("未分类");
        assertThat(stock.latestPrice()).isNull();
        assertThat(snapshot.dataGaps()).singleElement().asString().contains("实时行情补充失败");
    }

    @Test
    void fetchSnapshotFallsBackToPreviousYearWhenLatestIsEmpty() {
        when(eastMoneyClient.fetchAnnualIndicators(latestFullYear, WHOLE_MARKET_LIMIT)).thenReturn(Map.of());
        Map<String, EastMoneyAnnualIndicator> previous = new LinkedHashMap<>();
        previous.put("600001", indicator("600001", "甲银行", "0.072"));
        when(eastMoneyClient.fetchAnnualIndicators(eq(latestFullYear - 1), anyInt())).thenReturn(previous);
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(anyList(), anyInt())).thenReturn(List.of());

        HighDividendBoardSnapshot snapshot = service.fetchSnapshot(new BigDecimal("0.05"));

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.reportYear()).isEqualTo(latestFullYear - 1);
        assertThat(snapshot.totalCount()).isEqualTo(1);
        assertThat(snapshot.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("回退使用 " + (latestFullYear - 1)));
    }

    @Test
    void fetchSnapshotUnavailableWhenAllYearFetchesFail() {
        when(eastMoneyClient.fetchAnnualIndicators(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("数据源获取失败"));

        HighDividendBoardSnapshot snapshot = service.fetchSnapshot(new BigDecimal("0.05"));

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.unavailableReason()).contains("年报指标获取失败");
        assertThat(snapshot.stocks()).isEmpty();
        assertThat(snapshot.totalCount()).isZero();
    }

    @Test
    void fetchSnapshotExcludesDividendsExceedingProfit() {
        Map<String, EastMoneyAnnualIndicator> indicators = new LinkedHashMap<>();
        indicators.put("600010", indicator("600010", "超额分红", "0.081", new BigDecimal("1.00"), "10派15.00元(含税)"));
        indicators.put("600011", indicator("600011", "亏损分红", "0.072", new BigDecimal("-0.50"), "10派2.00元(含税)"));
        indicators.put("600012", indicator("600012", "正常分红", "0.062", new BigDecimal("2.00"), "10派10.00元(含税)"));
        indicators.put("600013", indicator("600013", "方案待核", "0.058", new BigDecimal("2.00"), null));
        when(eastMoneyClient.fetchAnnualIndicators(latestFullYear, WHOLE_MARKET_LIMIT)).thenReturn(indicators);
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(anyList(), anyInt())).thenReturn(List.of());

        HighDividendBoardSnapshot snapshot = service.fetchSnapshot(new BigDecimal("0.05"));

        assertThat(snapshot.stocks()).extracting(HighDividendStock::symbol).containsExactly("600012", "600013");
        assertThat(snapshot.stocks().get(0).payoutRatio()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(snapshot.stocks().get(1).payoutRatio()).isNull();
        assertThat(snapshot.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("剔除 2 只分红超过当年利润"));
    }

    @Test
    void fetchSnapshotExcludesExcessiveYieldOutliersAndNonMainlandSymbols() {
        Map<String, EastMoneyAnnualIndicator> indicators = new LinkedHashMap<>();
        indicators.put("600900", indicator("600900", "异常沪市", "0.40"));
        indicators.put("839710", indicator("839710", "北交所样本", "0.22"));
        indicators.put("600001", indicator("600001", "甲银行", "0.081"));
        when(eastMoneyClient.fetchAnnualIndicators(latestFullYear, WHOLE_MARKET_LIMIT)).thenReturn(indicators);
        when(eastMoneyClient.fetchEastMoneyQuotesBySymbols(anyList(), anyInt())).thenReturn(List.of());

        HighDividendBoardSnapshot snapshot = service.fetchSnapshot(new BigDecimal("0.05"));

        assertThat(snapshot.stocks()).hasSize(1);
        assertThat(snapshot.stocks().get(0).symbol()).isEqualTo("600001");
        assertThat(snapshot.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("剔除 1 只股息率超过 30%"));
    }

    @Test
    void normalizeThresholdDefaultsClampsAndConvertsToFraction() {
        assertThat(HighDividendBoardService.normalizeThreshold(null))
                .isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(HighDividendBoardService.normalizeThreshold(new BigDecimal("5")))
                .isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(HighDividendBoardService.normalizeThreshold(new BigDecimal("0.1")))
                .isEqualByComparingTo(new BigDecimal("0.005"));
        assertThat(HighDividendBoardService.normalizeThreshold(new BigDecimal("50")))
                .isEqualByComparingTo(new BigDecimal("0.2"));
    }

    private EastMoneyAnnualIndicator indicator(String symbol, String name, String dividendYield) {
        return indicator(symbol, name, dividendYield, new BigDecimal("1.60"), "10派2.5元(含税)");
    }

    private EastMoneyAnnualIndicator indicator(
            String symbol,
            String name,
            String dividendYield,
            BigDecimal eps,
            String description
    ) {
        return new EastMoneyAnnualIndicator(
                symbol,
                name,
                latestFullYear + "-12-31",
                latestFullYear + "年 年报",
                new BigDecimal("11.20"),
                new BigDecimal("1.80"),
                new BigDecimal("30.00"),
                new BigDecimal("4.50"),
                new BigDecimal("3.20"),
                eps,
                new BigDecimal("9.80"),
                new BigDecimal("210000000000"),
                new BigDecimal("68000000000"),
                description,
                dividendYield == null ? null : new BigDecimal(dividendYield)
        );
    }

    private EastMoneyQuote quote(
            String symbol,
            String name,
            String industry,
            String latestPrice,
            String changePercent,
            String peTtm,
            String pbRatio
    ) {
        return new EastMoneyQuote(
                symbol,
                name,
                "SH",
                industry,
                new BigDecimal(latestPrice),
                new BigDecimal(changePercent),
                new BigDecimal("0.80"),
                new BigDecimal("900000"),
                new BigDecimal("120000000"),
                null,
                new BigDecimal(pbRatio),
                new BigDecimal(peTtm),
                "东方财富",
                null,
                Instant.parse("2026-08-22T01:30:00Z"),
                null,
                null
        );
    }
}

package com.aistock.research.market;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyGlobalQuote;
import com.aistock.research.integration.yahoo.YahooFinanceClient;
import com.aistock.research.integration.yahoo.YahooSparkQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverseasMarketBoardServiceTest {

    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final YahooFinanceClient yahooFinanceClient = mock(YahooFinanceClient.class);
    private final OverseasMarketBoardService service = new OverseasMarketBoardService(eastMoneyClient, yahooFinanceClient);

    @Test
    void shouldAggregateThreeMarketsWithSortedIndustries() {
        stubEastQuotes(eastQuotes());
        when(yahooFinanceClient.fetchSparkQuotes(anyList())).thenReturn(Map.of(
                "^KQ11", spark("^KQ11", "760", "0.44"),
                "1631.T", spark("1631.T", "610", "2.00"),
                "1617.T", spark("1617.T", "11800", "-1.00"),
                "091160.KS", spark("091160.KS", "41000", "3.30"),
                "091170.KS", spark("091170.KS", "15835", "-0.50")
        ));

        OverseasMarketBoardSnapshot snapshot = service.fetchSnapshot();

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.globalIndices())
                .extracting(OverseasIndexQuote::name)
                .containsExactly("恒生指数", "德国DAX", "英国富时100", "欧洲斯托克50", "法国CAC40");

        OverseasMarketSection us = section(snapshot, "US");
        assertThat(us.indices()).extracting(OverseasIndexQuote::name).containsExactly("道琼斯", "标普500", "纳斯达克");
        assertThat(us.industries()).isSortedAccordingTo(
                java.util.Comparator.comparing(OverseasIndexQuote::changePercent,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        assertThat(us.industries().get(0).name()).isEqualTo("信息技术");
        assertThat(us.industries().get(0).currency()).isEqualTo("USD");
        assertThat(us.unavailableReason()).isNull();

        OverseasMarketSection jp = section(snapshot, "JP");
        assertThat(jp.indices()).extracting(OverseasIndexQuote::name).containsExactly("日经225");
        assertThat(jp.industries()).extracting(OverseasIndexQuote::name).containsExactly("银行", "食品·农林水产");
        assertThat(jp.industries().get(0).changePercent()).isEqualByComparingTo("2.00");
        assertThat(jp.industries().get(0).currency()).isEqualTo("JPY");

        OverseasMarketSection kr = section(snapshot, "KR");
        assertThat(kr.indices()).extracting(OverseasIndexQuote::name).containsExactly("韩国KOSPI", "KOSDAQ");
        assertThat(kr.industries()).extracting(OverseasIndexQuote::name).containsExactly("半导体", "银行");

        assertThat(snapshot.markets()).allSatisfy(market ->
                assertThat(market.status()).isIn("OPEN", "CLOSED"));
    }

    @Test
    void shouldDegradeJapanAndKoreaIndustriesWhenYahooFails() {
        stubEastQuotes(eastQuotes());
        when(yahooFinanceClient.fetchSparkQuotes(anyList()))
                .thenThrow(new IllegalStateException("Yahoo Finance 行情数据获取失败"));

        OverseasMarketBoardSnapshot snapshot = service.fetchSnapshot();

        assertThat(snapshot.available()).isTrue();
        OverseasMarketSection us = section(snapshot, "US");
        assertThat(us.industries()).hasSize(11);
        assertThat(us.unavailableReason()).isNull();

        OverseasMarketSection jp = section(snapshot, "JP");
        assertThat(jp.industries()).isEmpty();
        assertThat(jp.unavailableReason()).contains("Yahoo");
        assertThat(jp.indices()).extracting(OverseasIndexQuote::name).containsExactly("日经225");

        OverseasMarketSection kr = section(snapshot, "KR");
        assertThat(kr.industries()).isEmpty();
        assertThat(kr.unavailableReason()).contains("Yahoo");

        assertThat(snapshot.dataGaps()).anySatisfy(gap -> assertThat(gap).contains("Yahoo"));
    }

    @Test
    void shouldReturnUnavailableWhenEastMoneyFails() {
        when(eastMoneyClient.fetchGlobalQuotes(anyList()))
                .thenThrow(new IllegalStateException("东方财富全球行情数据获取失败"));

        OverseasMarketBoardSnapshot snapshot = service.fetchSnapshot();

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.unavailableReason()).contains("东方财富全球行情获取失败");
        assertThat(snapshot.markets()).isEmpty();
    }

    @Test
    void shouldRetryImmediatelyWhenUnavailableSnapshotWasNotCached() {
        when(eastMoneyClient.fetchGlobalQuotes(anyList()))
                .thenThrow(new IllegalStateException("东方财富全球行情数据获取失败"))
                .thenReturn(eastQuotes());
        when(yahooFinanceClient.fetchSparkQuotes(anyList())).thenReturn(Map.of());

        assertThat(service.snapshot().available()).isFalse();
        OverseasMarketBoardSnapshot recovered = service.snapshot();
        assertThat(recovered.available()).isTrue();

        // 可用快照进入缓存：再次读取不再触达数据源
        when(eastMoneyClient.fetchGlobalQuotes(anyList()))
                .thenThrow(new IllegalStateException("不应再次请求"));
        assertThat(service.snapshot().available()).isTrue();
    }

    @Test
    void shouldEstimateMarketStatusByExchangeLocalSession() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        assertThat(OverseasMarketBoardService.marketStatus(
                ZonedDateTime.of(2026, 8, 24, 10, 0, 0, 0, tokyo), LocalTime.of(9, 0), LocalTime.of(15, 0)))
                .isEqualTo("OPEN");
        assertThat(OverseasMarketBoardService.marketStatus(
                ZonedDateTime.of(2026, 8, 24, 8, 59, 0, 0, tokyo), LocalTime.of(9, 0), LocalTime.of(15, 0)))
                .isEqualTo("CLOSED");
        assertThat(OverseasMarketBoardService.marketStatus(
                ZonedDateTime.of(2026, 8, 24, 15, 0, 0, 0, tokyo), LocalTime.of(9, 0), LocalTime.of(15, 0)))
                .isEqualTo("CLOSED");
        assertThat(OverseasMarketBoardService.marketStatus(
                ZonedDateTime.of(2026, 8, 23, 10, 0, 0, 0, tokyo), LocalTime.of(9, 0), LocalTime.of(15, 0)))
                .isEqualTo("CLOSED");
    }

    private OverseasMarketSection section(OverseasMarketBoardSnapshot snapshot, String market) {
        return snapshot.markets().stream()
                .filter(item -> market.equals(item.market()))
                .findFirst()
                .orElseThrow();
    }

    private void stubEastQuotes(List<EastMoneyGlobalQuote> quotes) {
        when(eastMoneyClient.fetchGlobalQuotes(anyList())).thenReturn(quotes);
    }

    private static List<EastMoneyGlobalQuote> eastQuotes() {
        Instant quoteTime = Instant.parse("2026-08-21T20:00:00Z");
        return List.of(
                global("DJIA", "道琼斯", "53277.01", "0.98", "517.80", quoteTime),
                global("SPX", "标普500", "7674.37", "0.43", "33.21", quoteTime),
                global("NDX", "纳斯达克", "26180.45", "0.43", "113.28", quoteTime),
                global("N225", "日经225", "65960.30", "-0.08", "-56.06", quoteTime),
                global("KS11", "韩国KOSPI", "6847.43", "-0.95", "-65.52", quoteTime),
                global("HSI", "恒生指数", "25000", "1.21", "300", quoteTime),
                global("GDAXI", "德国DAX", "24000", "0.59", "140", quoteTime),
                global("FTSE", "英国富时100", "9700", "0.64", "62", quoteTime),
                global("SX5E", "欧洲斯托克50", "5900", "0.63", "37", quoteTime),
                global("FCHI", "法国CAC40", "8800", "0.37", "32", quoteTime),
                global("XLK", "科技行业精选指数ETF-SPDR", "230", "1.50", "3.40", quoteTime),
                global("XLF", "金融ETF-SPDR", "48", "0.93", "0.44", quoteTime),
                global("XLE", "能源指数ETF-SPDR", "87", "-0.17", "-0.15", quoteTime),
                global("XLV", "医疗保健ETF-SPDR", "140", "0.30", "0.42", quoteTime),
                global("XLI", "工业ETF-SPDR", "125", "0.20", "0.25", quoteTime),
                global("XLY", "可选消费ETF-SPDR", "210", "0.10", "0.21", quoteTime),
                global("XLP", "必需消费ETF-SPDR", "78", "-0.05", "-0.04", quoteTime),
                global("XLB", "原材料ETF-SPDR", "90", "-0.10", "-0.09", quoteTime),
                global("XLRE", "房地产ETF-SPDR", "41", "-0.20", "-0.08", quoteTime),
                global("XLU", "公用事业ETF-SPDR", "76", "-0.25", "-0.19", quoteTime),
                global("XLC", "通信服务ETF-SPDR", "95", "-0.30", "-0.28", quoteTime)
        );
    }

    private static EastMoneyGlobalQuote global(
            String code, String name, String price, String changePercent, String changeValue, Instant quoteTime
    ) {
        return new EastMoneyGlobalQuote(
                code, name, bd(price), bd(changePercent), bd(changeValue), null, 100, quoteTime);
    }

    private static YahooSparkQuote spark(String symbol, String lastClose, String changePercent) {
        return new YahooSparkQuote(symbol, bd(lastClose), bd(changePercent), Instant.parse("2026-08-21T06:00:00Z"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

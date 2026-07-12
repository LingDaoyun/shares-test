package com.aistock.research.tradefeedback;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyKLineSeries;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EastMoneyTradeMarketDataGatewayTest {

    @Test
    void fallsBackToTencentWhenEastMoneyDoesNotReturnTheRequestedSymbol() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        when(client.fetchEastMoneyQuotesBySymbols(List.of("002714"), 1))
                .thenReturn(List.of(quote("000001", "10")));
        when(client.fetchTencentQuotes(List.of("002714"), 1))
                .thenReturn(List.of(quote("002714", "12")));
        EastMoneyTradeMarketDataGateway gateway = new EastMoneyTradeMarketDataGateway(client);

        LatestMarketPrice result = gateway.latestPrice("002714").orElseThrow();

        assertThat(result.price()).isEqualByComparingTo("12");
        assertThat(result.source()).isEqualTo("TENCENT_LIVE_QUOTE_FALLBACK");
        assertThat(result.tradeDate()).isEqualTo(LocalDate.parse("2026-07-11"));
        assertThat(result.marketTimestamp()).isEqualTo(Instant.parse("2026-07-11T07:00:00Z"));
    }

    @Test
    void preservesTheQuotesActualTradeDateAndMarketTimestamp() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        when(client.fetchEastMoneyQuotesBySymbols(List.of("002714"), 1))
                .thenReturn(List.of(quote("002714", "12")));
        EastMoneyTradeMarketDataGateway gateway = new EastMoneyTradeMarketDataGateway(client);

        LatestMarketPrice result = gateway.latestPrice("002714").orElseThrow();

        assertThat(result.tradeDate()).isEqualTo(LocalDate.parse("2026-07-11"));
        assertThat(result.marketTimestamp()).isEqualTo(Instant.parse("2026-07-11T07:00:00Z"));
    }

    @Test
    void rejectsQuotesWithoutTrustworthyMarketTime() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        when(client.fetchEastMoneyQuotesBySymbols(List.of("002714"), 1))
                .thenReturn(List.of(undatedQuote("002714", "12")));
        when(client.fetchTencentQuotes(List.of("002714"), 1))
                .thenReturn(List.of(undatedQuote("002714", "13")));
        EastMoneyTradeMarketDataGateway gateway = new EastMoneyTradeMarketDataGateway(client);

        assertThat(gateway.latestPrice("002714")).isEmpty();
    }

    @Test
    void propagatesEastMoneyFailureWhenTencentHasNoValidFallback() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        when(client.fetchEastMoneyQuotesBySymbols(List.of("002714"), 1))
                .thenThrow(new IllegalStateException("east money down"));
        when(client.fetchTencentQuotes(List.of("002714"), 1)).thenReturn(List.of());
        EastMoneyTradeMarketDataGateway gateway = new EastMoneyTradeMarketDataGateway(client);

        assertThatThrownBy(() -> gateway.latestPrice("002714"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("east money down");
    }

    @Test
    void preservesHistoricalProviderAndRejectsPartialSeriesAtTheOutcomeBoundary() {
        EastMoneyClient client = mock(EastMoneyClient.class);
        LocalDate begin = LocalDate.parse("2025-01-01");
        LocalDate end = LocalDate.parse("2026-07-11");
        EastMoneyKLine partialRow = new EastMoneyKLine(
                "002714", LocalDate.parse("2025-01-02"), new BigDecimal("10"),
                new BigDecimal("11"), new BigDecimal("12"), new BigDecimal("9"), null, null);
        when(client.fetchDailyKLineSeries("002714", begin, end)).thenReturn(
                new EastMoneyKLineSeries(
                        List.of(partialRow), "TENCENT_QFQ_DAILY_PARTIAL", false,
                        "仅完成 1/2 个分片"));
        EastMoneyTradeMarketDataGateway gateway = new EastMoneyTradeMarketDataGateway(client);

        MarketKLineSeries result = gateway.dailyKLineSeries("002714", begin, end);

        assertThat(result.complete()).isFalse();
        assertThat(result.sourceName()).isEqualTo("TENCENT_QFQ_DAILY_PARTIAL");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.detail()).contains("1/2");
    }

    private EastMoneyQuote quote(String symbol, String price) {
        return new EastMoneyQuote(
                symbol, symbol, "SZ", null, new BigDecimal(price), null, null, null, null,
                null, null, null, "test", null, Instant.parse("2026-07-12T01:00:00Z"),
                LocalDate.parse("2026-07-11"), Instant.parse("2026-07-11T07:00:00Z"));
    }

    private EastMoneyQuote undatedQuote(String symbol, String price) {
        return new EastMoneyQuote(
                symbol, symbol, "SZ", null, new BigDecimal(price), null, null, null, null,
                null, null, null, "test", null, Instant.parse("2026-07-12T01:00:00Z"));
    }
}

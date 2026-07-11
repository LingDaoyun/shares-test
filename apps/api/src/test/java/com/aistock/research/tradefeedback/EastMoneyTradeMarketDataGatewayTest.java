package com.aistock.research.tradefeedback;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
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

    private EastMoneyQuote quote(String symbol, String price) {
        return new EastMoneyQuote(
                symbol, symbol, "SZ", null, new BigDecimal(price), null, null, null, null,
                null, null, null, "test", null, Instant.parse("2026-07-12T01:00:00Z"));
    }
}

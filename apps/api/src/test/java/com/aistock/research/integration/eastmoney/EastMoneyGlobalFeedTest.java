package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastMoneyGlobalFeedTest {
    @Test
    void fetchesRequestedIndicesAndEtfsWithTheirOwnPricePrecision() {
        FeedClient client = new FeedClient("""
                {"rc":0,"data":{"total":2,"diff":[
                  {"f12":"XLK","f13":107,"f14":"信息技术ETF","f1":3,"f152":2,
                   "f2":174360,"f3":323,"f4":5460,"f18":168900,"f124":1794423600},
                  {"f12":"DJIA","f13":100,"f14":"道琼斯","f1":2,"f152":2,
                   "f2":5248649,"f3":49,"f4":25460,"f18":5223189,"f124":1794423600}
                ]}}
                """);

        var rows = client.fetchGlobalQuotes(List.of("100.DJIA", "107.XLK"));

        assertThat(client.requestedUrl).startsWith("https://data.eastmoney.com/dataapi/bkzj/getbkzj?")
                .contains("code=i%3A100.DJIA%2Ci%3A107.XLK").contains("f1").contains("f152").contains("f124");
        assertThat(rows).hasSize(2);
        var etf = rows.stream().filter(row -> row.code().equals("XLK")).findFirst().orElseThrow();
        assertThat(etf.latestPrice()).isEqualByComparingTo("174.360");
        assertThat(etf.changePercent()).isEqualByComparingTo("3.23");
        assertThat(etf.changeValue()).isEqualByComparingTo("5.460");
        assertThat(etf.prevClose()).isEqualByComparingTo("168.900");
        assertThat(etf.marketCode()).isEqualTo(107);
        assertThat(etf.quoteTime()).isEqualTo(Instant.ofEpochSecond(1794423600));
        assertThat(rows.stream().filter(row -> row.code().equals("DJIA")).findFirst().orElseThrow().latestPrice())
                .isEqualByComparingTo("52486.49");
    }

    @Test
    void doesNotInventAQuoteTimestampOrConvertMissingMetricsToZero() {
        var rows = new FeedClient("""
                {"rc":0,"data":{"total":1,"diff":[
                  {"f12":"XLK","f13":107,"f1":3,"f152":2,"f2":174360,"f3":"-"}
                ]}}
                """).fetchGlobalQuotes(List.of("107.XLK"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.quoteTime()).isNull();
            assertThat(row.changePercent()).isNull();
            assertThat(row.changeValue()).isNull();
        });
    }

    @Test
    void rejectsAnEmptyGlobalFeedInsteadOfShowingAnAvailableEmptyBoard() {
        FeedClient client = new FeedClient("{\"rc\":0,\"data\":{\"total\":0,\"diff\":[]}}");
        assertThatThrownBy(() -> client.fetchGlobalQuotes(List.of("100.DJIA")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("全球行情");
    }

    private static class FeedClient extends EastMoneyClient {
        private final String response;
        private String requestedUrl;

        FeedClient(String response) {
            super(null, new ObjectMapper(), null);
            this.response = response;
        }

        @Override
        String fetchBodyWithCurl(String url, Exception cause, String referer) {
            requestedUrl = url;
            return response;
        }
    }
}

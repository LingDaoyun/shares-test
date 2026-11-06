package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistorySourceAccessBlockedTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate BEGIN = LocalDate.of(2020, 1, 2);
    private static final LocalDate END = LocalDate.of(2022, 3, 1);
    private static final String WAF_PAGE =
            "<html><script>location.href='https://waf.tencent.com/501page.html?type=blocked';</script></html>";
    private static final String EMPTY_TENCENT = "{\"code\":0,\"data\":{\"sh600893\":{\"qfqday\":[]}}}";
    private static final String EMPTY_EASTMONEY = "{\"data\":{\"klines\":[]}}";

    @Test
    void accessChallengeStopsRemainingTencentSlicesButKeepsValidEastMoneyFallback() {
        Client client = new Client(url -> url.contains(",day," + BEGIN + ",")
                ? completeTencentSlice(url) : WAF_PAGE, validEastMoney(), false);

        EastMoneyKLineSeries result = client.fetchDailyKLineSeries("600893", BEGIN, END);

        assertThat(result.complete()).isTrue();
        assertThat(result.sourceName()).isEqualTo("EAST_MONEY_QFQ_DAILY");
        assertThat(result.rows()).extracting(EastMoneyKLine::tradeDate).containsExactly(BEGIN, END);
        assertThat(client.tencentUrls).hasSize(2);
        assertThat(client.eastMoneyUrls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accessChallengePropagatesWhenEastMoneyCannotSupplyACompleteSeries(boolean eastMoneyThrows) {
        Client client = new Client(url -> WAF_PAGE, EMPTY_EASTMONEY, eastMoneyThrows);

        assertThatThrownBy(() -> client.fetchDailyKLineSeries("600893", BEGIN, END))
                .isInstanceOf(HistorySourceAccessBlockedException.class);

        assertThat(client.tencentUrls).hasSize(1);
        assertThat(client.eastMoneyUrls).hasSize(1);
    }

    @Test
    void ordinaryEmptyStockHistoryKeepsAllSlicesAndReturnsAnIncompleteSeries() {
        Client client = new Client(url -> EMPTY_TENCENT, EMPTY_EASTMONEY, false);

        EastMoneyKLineSeries result = client.fetchDailyKLineSeries("600893", BEGIN, END);

        assertThat(result.complete()).isFalse();
        assertThat(result.sourceName()).isEqualTo("HISTORICAL_KLINE_PARTIAL");
        assertThat(result.rows()).isEmpty();
        assertThat(client.tencentUrls).hasSize(3);
        assertThat(client.eastMoneyUrls).hasSize(1);
    }

    @Test
    void unrelatedHtmlDoesNotBecomeAnAccessChallenge() {
        Client client = new Client(url -> "<html><body>temporary error</body></html>", EMPTY_EASTMONEY, false);

        EastMoneyKLineSeries result = client.fetchDailyKLineSeries("600893", BEGIN, END);

        assertThat(result.complete()).isFalse();
        assertThat(client.tencentUrls).hasSize(3);
        assertThat(client.eastMoneyUrls).hasSize(1);
    }

    @Test
    void cancelledTencentHistoryPropagatesWithoutRequestingEastMoney() {
        CancellationException cancelled = new CancellationException("Tencent history cancelled");
        Client client = new Client(url -> { throw cancelled; }, validEastMoney(), false);

        assertThatThrownBy(() -> client.fetchDailyKLineSeries("600893", BEGIN, END))
                .isSameAs(cancelled);

        assertThat(client.tencentUrls).hasSize(1);
        assertThat(client.eastMoneyUrls).isEmpty();
    }

    @Test
    void cancelledEastMoneyFallbackAfterTencentChallengeRemainsCancellation() {
        CancellationException cancelled = new CancellationException("EastMoney fallback cancelled");
        Client client = new Client(url -> WAF_PAGE, EMPTY_EASTMONEY, false);
        client.eastMoneyFailure = cancelled;

        assertThatThrownBy(() -> client.fetchDailyKLineSeries("600893", BEGIN, END))
                .isSameAs(cancelled);

        assertThat(client.tencentUrls).hasSize(1);
        assertThat(client.eastMoneyUrls).hasSize(1);
    }

    private static String completeTencentSlice(String url) {
        String[] fields = url.substring(url.indexOf("?param=") + 7).split(",", -1);
        List<List<String>> rows = List.of(
                List.of(fields[2], "10", "11", "12", "9", "100"),
                List.of(fields[3], "10", "11", "12", "9", "100"));
        return JSON.valueToTree(Map.of("data", Map.of("sh600893", Map.of("qfqday", rows)))).toString();
    }

    private static String validEastMoney() {
        return JSON.valueToTree(Map.of("data", Map.of("klines", List.of(
                BEGIN + ",10,11,12,9,100,1100",
                END + ",10,11,12,9,100,1100")))).toString();
    }

    private static final class Client extends EastMoneyClient {
        private final Function<String, String> tencentResponse;
        private final String eastMoneyResponse;
        private final boolean eastMoneyThrows;
        private RuntimeException eastMoneyFailure;
        private final List<String> tencentUrls = new ArrayList<>();
        private final List<String> eastMoneyUrls = new ArrayList<>();

        private Client(Function<String, String> tencentResponse, String eastMoneyResponse, boolean eastMoneyThrows) {
            super(null, JSON, null);
            this.tencentResponse = tencentResponse;
            this.eastMoneyResponse = eastMoneyResponse;
            this.eastMoneyThrows = eastMoneyThrows;
        }

        @Override
        String fetchBodyWithCurl(String url, Exception failure, String referer) {
            if (url.contains("/fqkline/get")) {
                tencentUrls.add(url);
                return tencentResponse.apply(url);
            }
            if (url.contains("/api/qt/stock/kline/get")) {
                eastMoneyUrls.add(url);
                if (eastMoneyFailure != null) throw eastMoneyFailure;
                if (eastMoneyThrows) throw new IllegalStateException("EastMoney transport unavailable");
                return eastMoneyResponse;
            }
            if (url.contains("CN_MarketData.getKLineData")) return "null";
            if (url.endsWith("/qfq.js")) return "var sh600893qfq={\"total\":0,\"data\":[]};";
            throw new AssertionError("Unexpected provider request in local fixture: " + url);
        }
    }
}

package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SinaDailyFallbackTest {
    private static final LocalDate BEGIN = LocalDate.of(2025, 1, 2);
    private static final LocalDate END = LocalDate.of(2025, 1, 3);
    private static final String WAF = "<html>https://waf.tencent.com/501page.html</html>";

    @Test
    void dailyAndFactorRequestsKeepPacingWithoutConsumingTheFullScanBudget() {
        EastMoneyClient client = new EastMoneyClient(null, new ObjectMapper(), null);
        long started = System.nanoTime();
        for (int index = 0; index < 5; index++) {
            ReflectionTestUtils.invokeMethod(client, "throttleRemote", index % 2 == 0
                    ? "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData"
                    : "https://finance.sina.com.cn/realstock/company/sh600000/qfq.js");
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsed).as("a full scan must retain pacing without spending over half its deadline only queued")
                .isBetween(720L, 2_000L);
    }

    @Test
    void independentDailySourceRestoresACompleteWindowAfterBothExistingSourcesFail() {
        Client client = new Client();

        EastMoneyKLineSeries result = client.fetchDailyKLineSeries("600000", BEGIN, END);

        assertThat(result.complete()).isTrue();
        assertThat(result.sourceName()).isEqualTo("SINA_QFQ_DAILY");
        assertThat(result.rows()).extracting(EastMoneyKLine::tradeDate).containsExactly(BEGIN, END);
        assertThat(result.rows().get(0).close()).isEqualByComparingTo("11");
        assertThat(result.rows().get(0).volume()).isEqualByComparingTo("120");
        assertThat(client.sinaRequests()).isEqualTo(2);
    }

    @Test
    void beijingSecurityUsesTheSameVerifiedDailyAndAdjustmentContract() {
        Client client = new Client();

        EastMoneyKLineSeries result = client.fetchDailyKLineSeries("920000", BEGIN, END);

        assertThat(result.complete()).isTrue();
        assertThat(result.sourceName()).isEqualTo("SINA_QFQ_DAILY");
        assertThat(result.rows()).hasSize(2);
        assertThat(client.sinaRequests()).isEqualTo(2);
    }

    @Test
    void aKnownChallengeAndTransportFailureAreNotRetriedForEveryOtherStock() {
        Client client = new Client();

        assertThat(client.fetchDailyKLineSeries("600000", BEGIN, END).complete()).isTrue();
        assertThat(client.fetchDailyKLineSeries("600001", BEGIN, END).complete()).isTrue();

        assertThat(client.tencentRequests()).isEqualTo(1);
        assertThat(client.eastMoneyRequests()).isEqualTo(1);
        assertThat(client.sinaRequests()).isEqualTo(4);
    }

    @Test
    void expiredSourceCooldownAllowsTheOriginalSourcesToBeCheckedAgain() {
        Client client = new Client();
        client.fetchDailyKLineSeries("600000", BEGIN, END);
        ReflectionTestUtils.setField(client, "tencentHistoryFailure", null);
        ReflectionTestUtils.setField(client, "eastMoneyHistoryFailure", null);

        client.fetchDailyKLineSeries("600001", BEGIN, END);

        assertThat(client.tencentRequests()).isEqualTo(2);
        assertThat(client.eastMoneyRequests()).isEqualTo(2);
    }

    @Test
    void anOrdinarySingleStockGapDoesNotDisableEitherOriginalSource() {
        Client client = new Client();
        client.challenge = false;
        client.transportFailure = false;

        client.fetchDailyKLineSeries("600000", BEGIN, END);
        client.fetchDailyKLineSeries("600001", BEGIN, END);

        assertThat(client.tencentRequests()).isEqualTo(2);
        assertThat(client.eastMoneyRequests()).isEqualTo(2);
    }

    @Test
    void cancelledSinaRequestRemainsCancellation() {
        Client client = new Client();
        CancellationException cancelled = new CancellationException("daily request cancelled");
        client.sinaFailure = cancelled;

        assertThatThrownBy(() -> client.fetchDailyKLineSeries("600000", BEGIN, END)).isSameAs(cancelled);
    }

    @Test
    void incompleteIndependentHistoryCannotTurnSourceBlockingIntoSuccess() {
        Client client = new Client();
        client.factorMismatch = true;

        assertThatThrownBy(() -> client.fetchDailyKLineSeries("600000", BEGIN, END))
                .isInstanceOf(HistorySourceAccessBlockedException.class)
                .hasMessageContaining("东方财富")
                .hasMessageContaining("新浪");
        assertThat(client.sinaRequests()).isEqualTo(2);
    }

    @Test
    void aValidatedButShortSingleStockHistoryRemainsADataGapRatherThanAGlobalSourceBlock() {
        Client client = new Client();

        EastMoneyKLineSeries result = client.fetchDailyKLineSeries("600000", BEGIN.minusMonths(3), END);

        assertThat(result.complete()).isFalse();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.detail()).contains("起始边界缺口过大", "拒绝使用部分数据");
    }

    @Test
    void unsupportedFallbackSecurityKeepsItsDataGapWithoutDeclaringTheWholeSourceBlocked() {
        Client client = new Client();

        assertThatThrownBy(() -> client.fetchDailyKLines("689009", BEGIN, END))
                .isInstanceOf(IncompleteKLineHistoryException.class)
                .hasMessageContaining("不支持该证券类型");
        assertThat(client.sinaRequests()).isZero();
    }

    @Test
    void validatedPartialHistoryIsExplicitlyMarkedSoTheFirstStockCannotAbortTheWholeScan() {
        Client client = new Client();

        assertThatThrownBy(() -> client.fetchDailyKLines("600000", BEGIN.minusMonths(3), END))
                .isInstanceOf(IncompleteKLineHistoryException.class);
    }

    @Test
    void threeTransportFailuresCannotBeDowngradedToASingleStockGap() {
        Client client = new Client();
        client.tencentTransportFailure = true;
        client.sinaFailure = new IllegalStateException("sina transport EOF");

        assertThatThrownBy(() -> client.fetchDailyKLines("600000", BEGIN, END))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(IncompleteKLineHistoryException.class)
                .hasMessageContaining("transport EOF");
    }

    @Test
    void anExistingCompleteSourceDoesNotFetchTheAdditionalSource() {
        Client client = new Client();
        client.eastMoneyHealthy = true;

        EastMoneyKLineSeries result = client.fetchDailyKLineSeries("600000", BEGIN, END);

        assertThat(result.complete()).isTrue();
        assertThat(result.sourceName()).isEqualTo("EAST_MONEY_QFQ_DAILY");
        assertThat(client.sinaRequests()).isZero();
    }

    private static final class Client extends EastMoneyClient {
        private final List<String> requests = new ArrayList<>();
        private boolean challenge = true;
        private boolean tencentTransportFailure;
        private boolean transportFailure = true;
        private boolean eastMoneyHealthy;
        private boolean factorMismatch;
        private RuntimeException sinaFailure;

        Client() { super(null, new ObjectMapper(), null); }

        @Override
        String fetchBodyWithCurl(String url, Exception failure, String referer) {
            requests.add(url);
            if (url.contains("/fqkline/get")) {
                if (tencentTransportFailure) throw new IllegalStateException("tencent transport EOF");
                return challenge ? WAF : "{\"data\":{}}";
            }
            if (url.contains("/api/qt/stock/kline/get")) {
                if (eastMoneyHealthy) return "{\"data\":{\"klines\":[\"2025-01-02,10,11,12,9,120,10000\",\"2025-01-03,10,11,12,9,130,11000\"]}}";
                if (transportFailure) throw new IllegalStateException("daily transport EOF");
                return "{\"data\":{\"klines\":[]}}";
            }
            if (sinaFailure != null) throw sinaFailure;
            if (url.contains("CN_MarketData.getKLineData")) return "[{\"day\":\"2025-01-02\",\"open\":\"20\",\"close\":\"22\",\"high\":\"24\",\"low\":\"18\",\"volume\":\"12000\"},{\"day\":\"2025-01-03\",\"open\":\"10\",\"close\":\"11\",\"high\":\"12\",\"low\":\"9\",\"volume\":\"13000\"}]";
            if (url.endsWith("/qfq.js")) {
                String code = url.substring(url.indexOf("/company/") + 9, url.indexOf("/qfq.js"));
                return "var " + code + "qfq={\"total\":" + (factorMismatch ? 3 : 2)
                        + ",\"data\":[{\"d\":\"2025-01-03\",\"f\":\"1\"},{\"d\":\"1900-01-01\",\"f\":\"2\"}]};";
            }
            throw new AssertionError("Unexpected URL: " + url);
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            throw new AssertionError("Historical-only window must not request current quotes");
        }

        long tencentRequests() { return requests.stream().filter(u -> u.contains("/fqkline/get")).count(); }
        long eastMoneyRequests() { return requests.stream().filter(u -> u.contains("/api/qt/stock/kline/get")).count(); }
        long sinaRequests() { return requests.stream().filter(u -> u.contains("sina.com.cn")).count(); }
    }
}

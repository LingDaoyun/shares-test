package com.aistock.research.integration.tushare;

import com.aistock.research.configuration.ShortTermChipSettings;
import com.aistock.research.shortterm.chip.ExternalChipPerformance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TushareChipClientTest {

    @Test
    void postsCyqPerfRequestAndParsesPercentagePointWinnerRate() {
        AtomicReference<TushareChipRequest> captured = new AtomicReference<>();
        TushareChipTransport transport = request -> {
            captured.set(request);
            return new TushareHttpResponse(200, """
                    {
                      "code": 0,
                      "msg": null,
                      "data": {
                        "fields": ["ts_code","trade_date","cost_5pct","cost_15pct","cost_50pct","cost_85pct","cost_95pct","weight_avg","winner_rate"],
                        "items": [["002580.SZ","20260730",9.10,9.40,10.00,10.60,11.00,10.20,63.50]]
                      }
                    }
                    """);
        };
        TushareChipClient client = client(transport, true, "secret-token");

        TushareChipFetchResult result = client.fetchPerformance("002580", LocalDate.of(2026, 7, 30));

        ExternalChipPerformance performance = result.value().orElseThrow();
        assertThat(performance.winnerRatePercent()).isEqualByComparingTo("63.50");
        assertThat(performance.averageCost()).isEqualByComparingTo("10.20");
        assertThat(captured.get().url()).isEqualTo("https://api.tushare.pro");
        assertThat(captured.get().jsonBody()).contains("\"api_name\":\"cyq_perf\"");
        assertThat(captured.get().jsonBody()).contains("\"ts_code\":\"002580.SZ\"");
        assertThat(captured.get().jsonBody()).contains("\"trade_date\":\"20260730\"");
    }

    @Test
    void mapsShanghaiAndBeijingCodesToTushareSuffixes() {
        AtomicReference<String> latestBody = new AtomicReference<>();
        TushareChipClient client = client(request -> {
            latestBody.set(request.jsonBody());
            return new TushareHttpResponse(200, emptyData());
        }, true, "secret-token");

        client.fetchPerformance("600000", LocalDate.of(2026, 7, 30));
        assertThat(latestBody.get()).contains("600000.SH");
        client.fetchPerformance("920001", LocalDate.of(2026, 7, 30));
        assertThat(latestBody.get()).contains("920001.BJ");
    }

    @Test
    void doesNotCallTransportWhenTokenIsMissing() {
        AtomicInteger calls = new AtomicInteger();
        TushareChipClient client = client(request -> {
            calls.incrementAndGet();
            return new TushareHttpResponse(200, emptyData());
        }, true, "");

        TushareChipFetchResult result = client.fetchPerformance("002580", LocalDate.of(2026, 7, 30));

        assertThat(calls).hasValue(0);
        assertThat(result.value()).isEmpty();
        assertThat(result.errorSummary()).isEqualTo("Tushare筹码认证未配置");
    }

    @Test
    void isolatesRateLimitAndNeverLeaksTokenInErrorSummary() {
        TushareChipClient client = client(
                request -> new TushareHttpResponse(429, "secret-token request limited"),
                true,
                "secret-token"
        );

        TushareChipFetchResult result = client.fetchPerformance("002580", LocalDate.of(2026, 7, 30));

        assertThat(result.value()).isEmpty();
        assertThat(result.httpStatus()).isEqualTo(429);
        assertThat(result.errorSummary()).contains("HTTP 429").doesNotContain("secret-token");
    }

    @Test
    void rejectsPartialExternalRowsInsteadOfGuessingMissingFields() {
        TushareChipClient client = client(request -> new TushareHttpResponse(200, """
                {"code":0,"data":{"fields":["ts_code","trade_date","weight_avg"],
                "items":[["002580.SZ","20260730",10.20]]}}
                """), true, "secret-token");

        TushareChipFetchResult result = client.fetchPerformance("002580", LocalDate.of(2026, 7, 30));

        assertThat(result.value()).isEmpty();
        assertThat(result.errorSummary()).isEqualTo("Tushare筹码字段不完整");
    }

    @Test
    void limitsConcurrentExternalVerificationRequests() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        TushareChipClient client = client(request -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            entered.countDown();
            entered.await(150, TimeUnit.MILLISECONDS);
            active.decrementAndGet();
            return new TushareHttpResponse(200, validData());
        }, true, "secret-token", 1);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                start.await();
                return client.fetchPerformance("002580", LocalDate.of(2026, 7, 30));
            });
            var second = executor.submit(() -> {
                start.await();
                return client.fetchPerformance("002580", LocalDate.of(2026, 7, 30));
            });
            start.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).value()).isPresent();
            assertThat(second.get(2, TimeUnit.SECONDS).value()).isPresent();
            assertThat(maxActive).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private TushareChipClient client(TushareChipTransport transport, boolean enabled, String token) {
        return client(transport, enabled, token, 4);
    }

    private TushareChipClient client(
            TushareChipTransport transport,
            boolean enabled,
            String token,
            int maxConcurrency
    ) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.chip.tushare.enabled", Boolean.toString(enabled))
                .withProperty("research.short-term.chip.tushare.token", token)
                .withProperty("research.short-term.chip.tushare.max-concurrency", Integer.toString(maxConcurrency))
                .withProperty("research.short-term.chip.tushare.base-url", "https://api.tushare.pro");
        return new TushareChipClient(transport, new ObjectMapper(), new ShortTermChipSettings(environment));
    }

    private static String validData() {
        return """
                {"code":0,"data":{
                  "fields":["ts_code","trade_date","cost_5pct","cost_15pct","cost_50pct","cost_85pct","cost_95pct","weight_avg","winner_rate"],
                  "items":[["002580.SZ","20260730",9.10,9.40,10.00,10.60,11.00,10.20,63.50]]
                }}
                """;
    }

    private static String emptyData() {
        return "{\"code\":0,\"data\":{\"fields\":[],\"items\":[]}}";
    }
}

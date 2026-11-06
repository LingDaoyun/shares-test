package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class EastMoneyScanRecoveryTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    @Test
    void unavailableTurnoverSourceIsNotRetriedForEveryStockAndCanonicalRowsStayUnchanged() {
        AtomicInteger requests = new AtomicInteger();
        EastMoneyClient client = failingSupplementClient(requests);
        List<EastMoneyKLine> first = rows("600000");
        List<EastMoneyKLine> second = rows("000001");

        assertThat(client.enrichDailyKLineTurnover("600000", DATE, DATE, first)).isSameAs(first);
        assertThat(client.enrichDailyKLineTurnover("000001", DATE, DATE, second)).isSameAs(second);

        assertThat(requests).hasValue(1);
        assertThat(second.get(0).turnoverRate()).isNull();
    }

    @Test
    void turnoverSourceCanBeRetriedAfterTheCooldownExpires() {
        AtomicInteger requests = new AtomicInteger();
        EastMoneyClient client = failingSupplementClient(requests);
        client.enrichDailyKLineTurnover("600000", DATE, DATE, rows("600000"));
        ReflectionTestUtils.setField(client, "turnoverSupplementRetryAfterMillis", 0L);

        client.enrichDailyKLineTurnover("000001", DATE, DATE, rows("000001"));

        assertThat(requests).hasValue(2);
    }

    @Test
    void aStockWithoutSupplementRowsDoesNotDisableTheSourceForOtherStocks() {
        AtomicInteger requests = new AtomicInteger();
        EastMoneyClient client = new EastMoneyClient(null, new ObjectMapper(), null) {
            @Override
            String fetchBodyWithCurl(String url, Exception failure, String referer) {
                requests.incrementAndGet();
                return "{\"data\":{\"klines\":[]}}";
            }
        };

        client.enrichDailyKLineTurnover("600000", DATE, DATE, rows("600000"));
        client.enrichDailyKLineTurnover("000001", DATE, DATE, rows("000001"));

        assertThat(requests).hasValue(2);
    }

    @Test
    void interruptionDuringCurlCancelsPromptlyAndPreservesTheInterruptFlag() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            requested.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        EastMoneyClient client = new EastMoneyClient(null, new ObjectMapper(), null);
        Thread worker = new Thread(() -> {
            try {
                client.fetchBodyWithCurl("http://127.0.0.1:" + server.getAddress().getPort() + "/slow", null, null);
            } catch (Throwable thrown) {
                failure.set(thrown);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            worker.start();
            assertThat(requested.await(3, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(2_000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
            assertThat(interrupted).isTrue();
        } finally {
            release.countDown();
            worker.interrupt();
            worker.join(2_000);
            server.stop(0);
        }
    }

    @Test
    void anAlreadyCancelledRequestCannotStartAnotherRemoteAttempt() {
        EastMoneyClient client = new EastMoneyClient(null, new ObjectMapper(), null);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> client.fetchBodyWithCurl("http://127.0.0.1:1/unused", null, null))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedRestClientRequestStopsBeforeCallingProvider() {
        RestClient restClient = mock(RestClient.class);
        EastMoneyClient client = new EastMoneyClient(restClient, new ObjectMapper(), null);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client, "fetchBody", "http://127.0.0.1:1/unused"))
                    .isInstanceOf(CancellationException.class);
            verifyNoInteractions(restClient);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancelledTencentRequestPropagatesCancellation() {
        EastMoneyClient client = new EastMoneyClient(null, new ObjectMapper(), null);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> client.fetchTencentQuotes(List.of("600000", "000001"), 2))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private EastMoneyClient failingSupplementClient(AtomicInteger requests) {
        return new EastMoneyClient(null, new ObjectMapper(), null) {
            @Override
            String fetchBodyWithCurl(String url, Exception failure, String referer) {
                requests.incrementAndGet();
                throw new IllegalStateException("SSL unexpected eof while reading");
            }
        };
    }

    private List<EastMoneyKLine> rows(String symbol) {
        return List.of(new EastMoneyKLine(symbol, DATE, new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("12"), new BigDecimal("9"), new BigDecimal("1000"), null, null));
    }
}

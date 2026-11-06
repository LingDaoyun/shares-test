package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class EastMoneyCurlTransportTest {
    private final EastMoneyClient client = new EastMoneyClient(null, new ObjectMapper(), null);

    @Test
    void readsJsonLargerThanTheProcessPipeBeforeWaitingForCurlToExit() throws Exception {
        byte[] json = ("{\"rows\":\"" + "a".repeat(512 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpServer server = server();
        server.createContext("/large", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
            }
        });
        server.start();
        try {
            assertThat(fetch(url(server, "/large"), 6, 3)).isEqualTo(json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unfinishedResponseStillRespectsTheOriginalProcessDeadline() throws Exception {
        HttpServer server = server();
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/unfinished", exchange -> holdOpen(exchange, release, null));
        server.start();
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> fetch(url(server, "/unfinished"), 8, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("curl 请求超时");
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(4));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void cancellationDuringBodyReadStopsCurlAndPreservesTheInterruptFlag() throws Exception {
        HttpServer server = server();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch requested = new CountDownLatch(1);
        server.createContext("/cancel", exchange -> holdOpen(exchange, release, requested));
        server.start();
        String url = url(server, "/cancel");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                fetch(url, 8, 8);
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "test-cancel-large-curl");
        worker.setDaemon(true);
        try {
            worker.start();
            assertThat(requested.await(3, TimeUnit.SECONDS)).isTrue();
            ProcessHandle curl = ProcessHandle.current().children()
                    .filter(child -> Arrays.asList(child.info().arguments().orElse(new String[0])).contains(url))
                    .findFirst().orElseThrow();
            worker.interrupt();
            worker.join(2_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
            assertThat(interrupted).isTrue();
            curl.onExit().get(2, TimeUnit.SECONDS);
            assertThat(curl.isAlive()).isFalse();
        } finally {
            release.countDown();
            worker.interrupt();
            worker.join(2_000);
            server.stop(0);
        }
    }

    @Test
    void curlFailureKeepsStderrDiagnosticAndNeverReturnsThePartialJsonBody() throws Exception {
        HttpServer server = server();
        byte[] partialJson = "{\"rows\":[]}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/truncated", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, partialJson.length + 100);
                exchange.getResponseBody().write(partialJson);
            } catch (IOException expectedTruncation) {
                // Deliberately close before the advertised length to make curl write stderr.
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> fetch(url(server, "/truncated"), 4, 5))
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("curl 请求失败")
                    .hasStackTraceContaining("curl:");
        } finally {
            server.stop(0);
        }
    }

    private byte[] fetch(String url, int maxTimeSeconds, int processWaitSeconds) {
        return ReflectionTestUtils.invokeMethod(client, "fetchBytesOnceWithCurl",
                url, null, null, 2, maxTimeSeconds, processWaitSeconds, 0);
    }

    private HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void holdOpen(HttpExchange exchange, CountDownLatch release, CountDownLatch requested) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"rows\":[".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            if (requested != null) requested.countDown();
            try {
                release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

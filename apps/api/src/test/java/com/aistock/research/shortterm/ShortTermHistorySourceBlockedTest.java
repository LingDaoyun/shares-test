package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.eastmoney.HistorySourceAccessBlockedException;
import com.aistock.research.integration.eastmoney.IncompleteKLineHistoryException;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.trading.QuoteFreshnessService;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ShortTermHistorySourceBlockedTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-20T07:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final TradingClockService tradingClockService = new TradingClockService(CLOCK);
    private final ShortTermService service = new ShortTermService(
            eastMoneyClient,
            new EvidenceCompletenessService(),
            tradingClockService,
            new QuoteFreshnessService(tradingClockService, CLOCK));

    @Test
    void aValidatedSingleStockGapAtTheProbeDoesNotDiscardOtherStocks() throws Exception {
        List<EastMoneyKLine> healthyRows = rows("600001");
        when(eastMoneyClient.fetchDailyKLines(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    if ("600000".equals(invocation.getArgument(0)))
                        throw new IncompleteKLineHistoryException("已验证日线未覆盖请求窗口");
                    return healthyRows;
                });

        Map<String, List<EastMoneyKLine>> result = fetchHistories(List.of(quote("600000"), quote("600001")));

        assertThat(result).hasSize(2);
        assertThat(result.get("600000")).isEmpty();
        assertThat(result.get("600001")).containsExactlyElementsOf(healthyRows);
        verify(eastMoneyClient).fetchDailyKLines(eq("600000"), any(), any());
        verify(eastMoneyClient).fetchDailyKLines(eq("600001"), any(), any());
        verifyNoMoreInteractions(eastMoneyClient);
    }

    @Test
    void aRealSourceBlockAtTheProbeStillStopsBeforeRequestingOtherStocks() {
        HistorySourceAccessBlockedException blocked = new HistorySourceAccessBlockedException("全部历史源不可用");
        assertProbeFailureStopsOtherStocks(blocked);
    }

    @Test
    void anOrdinaryTransportFailureAtTheProbeStillStopsBeforeRequestingOtherStocks() {
        assertProbeFailureStopsOtherStocks(new IllegalStateException("历史请求传输失败"));
    }

    private void assertProbeFailureStopsOtherStocks(RuntimeException failure) {
        when(eastMoneyClient.fetchDailyKLines(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> fetchHistories(List.of(quote("600000"), quote("600001"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("K线数据源当前不可用")
                .hasCause(failure);
        verify(eastMoneyClient).fetchDailyKLines(eq("600000"), any(), any());
        verifyNoMoreInteractions(eastMoneyClient);
    }

    @Test
    void sourceBlockAfterSuccessfulProbeStopsBatchAndCancelsRemainingRequests() throws Exception {
        int workerCount = Math.max(1, ForkJoinPool.getCommonPoolParallelism());
        List<EastMoneyQuote> quotes = IntStream.range(0, workerCount + 5)
                .mapToObj(index -> quote(String.format("%06d", 600000 + index)))
                .toList();
        String probeSymbol = quotes.get(0).symbol();
        String blockedSymbol = quotes.get(1).symbol();
        HistorySourceAccessBlockedException blocked =
                new HistorySourceAccessBlockedException("历史行情源要求访问验证");
        List<String> startedSymbols = new CopyOnWriteArrayList<>();
        CountDownLatch activeSiblingStarted = new CountDownLatch(workerCount > 1 ? 1 : 0);
        CountDownLatch activeSiblingInterrupted = new CountDownLatch(1);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch batchFinished = new CountDownLatch(1);
        AtomicReference<Throwable> batchFailure = new AtomicReference<>();

        when(eastMoneyClient.fetchDailyKLines(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    String symbol = invocation.getArgument(0);
                    startedSymbols.add(symbol);
                    if (symbol.equals(probeSymbol)) return rows(symbol);
                    if (symbol.equals(blockedSymbol)) {
                        assertThat(activeSiblingStarted.await(2, TimeUnit.SECONDS))
                                .as("another history request is active before the source is blocked")
                                .isTrue();
                        throw blocked;
                    }
                    activeSiblingStarted.countDown();
                    try {
                        releaseWorkers.await();
                        return rows(symbol);
                    } catch (InterruptedException interrupted) {
                        activeSiblingInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("history request interrupted", interrupted);
                    }
                });

        Thread batchThread = new Thread(() -> {
            try {
                fetchHistories(quotes);
            } catch (Throwable failure) {
                batchFailure.set(failure);
            } finally {
                batchFinished.countDown();
            }
        }, "test-short-term-history-source-blocked");
        batchThread.setDaemon(true);
        batchThread.start();
        try {
            assertThat(batchFinished.await(2, TimeUnit.SECONDS))
                    .as("a source block must abort the batch without waiting for other stocks")
                    .isTrue();
            assertThat(batchFailure.get()).isSameAs(blocked);
            assertThat(startedSymbols.get(0)).isEqualTo(probeSymbol);
            assertThat(Collections.frequency(startedSymbols, probeSymbol)).isEqualTo(1);
            if (workerCount > 1) {
                assertThat(activeSiblingInterrupted.await(1, TimeUnit.SECONDS))
                        .as("active history requests must be interrupted when the batch fails")
                        .isTrue();
            }
            assertThat(startedSymbols)
                    .as("queued stocks must not drain after a source block")
                    .doesNotContain(quotes.get(quotes.size() - 1).symbol());
        } finally {
            batchThread.interrupt();
            releaseWorkers.countDown();
            batchThread.join(1000);
        }
    }

    @Test
    void ordinaryStockHistoryFailureKeepsSuccessfulRowsAndAnEmptyFailedEntry() throws Exception {
        String probeSymbol = "600000";
        String missingSymbol = "600001";
        String healthySymbol = "600002";
        List<EastMoneyKLine> probeRows = rows(probeSymbol);
        List<EastMoneyKLine> healthyRows = rows(healthySymbol);
        when(eastMoneyClient.fetchDailyKLines(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    String symbol = invocation.getArgument(0);
                    if (symbol.equals(probeSymbol)) return probeRows;
                    if (symbol.equals(missingSymbol)) throw new IllegalStateException("individual stock history unavailable");
                    if (symbol.equals(healthySymbol)) return healthyRows;
                    throw new AssertionError("unexpected stock: " + symbol);
                });

        Map<String, List<EastMoneyKLine>> result = fetchHistories(
                List.of(quote(probeSymbol), quote(missingSymbol), quote(healthySymbol)));

        assertThat(result).hasSize(3);
        assertThat(result.get(probeSymbol)).containsExactlyElementsOf(probeRows);
        assertThat(result.get(missingSymbol)).isEmpty();
        assertThat(result.get(healthySymbol)).containsExactlyElementsOf(healthyRows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<EastMoneyKLine>> fetchHistories(List<EastMoneyQuote> quotes) throws Exception {
        Method method = ShortTermService.class.getDeclaredMethod("fetchKLinesWithSourceGuard", List.class);
        method.setAccessible(true);
        try {
            return (Map<String, List<EastMoneyKLine>>) method.invoke(service, quotes);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException failure) throw failure;
            if (exception.getCause() instanceof Error failure) throw failure;
            throw new AssertionError("unexpected checked batch failure", exception.getCause());
        }
    }

    private static EastMoneyQuote quote(String symbol) {
        return new EastMoneyQuote(
                symbol, "测试股票", "SH", "测试行业",
                new BigDecimal("10"), BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("100000"), new BigDecimal("100000000"),
                new BigDecimal("15"), BigDecimal.ONE, new BigDecimal("15"),
                "test", "https://example.invalid/" + symbol, CLOCK.instant());
    }

    private static List<EastMoneyKLine> rows(String symbol) {
        return List.of(new EastMoneyKLine(
                symbol, LocalDate.now(CLOCK),
                new BigDecimal("9.9"), new BigDecimal("10"),
                new BigDecimal("10.1"), new BigDecimal("9.8"),
                new BigDecimal("100000"), new BigDecimal("100000000")));
    }
}

package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class FirstBoardBaselineService {

    static final String BASELINE_RULE_VERSION = "first-board-baseline-v2";

    private final FirstBoardMarketDataGateway gateway;
    private final FirstBoardMarketDayIngestionService ingestionService;
    private final FirstBoardDailyBarStore dailyBarStore;
    private final FirstBoardBaselineStore baselineStore;
    private final FirstBoardTechnicalEvaluator evaluator;
    private final TradingClockService clock;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "first-board-baseline-coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService evaluationExecutor = Executors.newFixedThreadPool(12, runnable -> {
        Thread thread = new Thread(runnable, "first-board-baseline-evaluator");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CompletableFuture<Void>> active = new ConcurrentHashMap<>();

    public FirstBoardBaselineService(
            FirstBoardMarketDataGateway gateway,
            FirstBoardMarketDayIngestionService ingestionService,
            FirstBoardDailyBarStore dailyBarStore,
            FirstBoardBaselineStore baselineStore,
            FirstBoardTechnicalEvaluator evaluator,
            TradingClockService clock
    ) {
        this.gateway = gateway;
        this.ingestionService = ingestionService;
        this.dailyBarStore = dailyBarStore;
        this.baselineStore = baselineStore;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    FirstBoardBaselineAccess access(LocalDate requestedTradeDate) {
        LocalDate scanDate = effectiveScanDate(requestedTradeDate);
        LocalDate baselineDate = clock.previousTradingDay(scanDate);
        FirstBoardMarketDay marketDay = dailyBarStore.marketDay(baselineDate).orElse(null);
        if (marketDay == null) {
            FirstBoardIngestionProgress progress = new FirstBoardIngestionProgress(
                    baselineDate, FirstBoardMarketDayStatus.IMPORTING, "INITIAL_IMPORT", 0, 0, 0,
                    null, null);
            startIngestion(scanDate);
            return FirstBoardBaselineAccess.initializing(progress);
        }
        if (marketDay.status() == FirstBoardMarketDayStatus.IMPORTING) {
            startIngestion(scanDate);
            return FirstBoardBaselineAccess.updating(FirstBoardIngestionProgress.from(marketDay));
        }
        if (marketDay.status() == FirstBoardMarketDayStatus.FAILED) {
            if (marketDay.nextRetryAt() == null || !Instant.now().isBefore(marketDay.nextRetryAt())) {
                startIngestion(scanDate);
            }
            return FirstBoardBaselineAccess.failed(FirstBoardIngestionProgress.from(marketDay));
        }

        Optional<FirstBoardBaselineSnapshot> ready = baselineStore.findReady(
                scanDate, baselineDate, BASELINE_RULE_VERSION, marketDay.barDataVersion());
        if (ready.isPresent()) {
            return FirstBoardBaselineAccess.ready(ready.get());
        }
        startBuild(scanDate, marketDay);
        return FirstBoardBaselineAccess.updating(new FirstBoardIngestionProgress(
                baselineDate, FirstBoardMarketDayStatus.READY, "CALCULATING_BASELINE",
                marketDay.writtenSymbolCount(), marketDay.expectedSymbolCount(),
                marketDay.failedSymbolCount(), null, null));
    }

    Optional<List<FirstBoardBar>> recentBars(LocalDate tradeDate, String symbol) {
        if (tradeDate == null || symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        LocalDate baselineDate = clock.previousTradingDay(tradeDate);
        List<com.aistock.research.integration.eastmoney.EastMoneyKLine> rows =
                dailyBarStore.recentBars(symbol, baselineDate, 20);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.stream().map(row -> new FirstBoardBar(
                row.tradeDate(), row.open(), row.close(), row.high(), row.low(), row.volume())).toList());
    }

    private void startIngestion(LocalDate scanDate) {
        String key = "ingest:" + scanDate;
        CompletableFuture<Void> marker = new CompletableFuture<>();
        if (active.putIfAbsent(key, marker) != null) {
            return;
        }
        CompletableFuture.runAsync(() -> ingestionService.ensureBaselineDayReady(scanDate), coordinator)
                .whenComplete((none, failure) -> {
                    if (failure == null) {
                        marker.complete(null);
                    } else {
                        marker.completeExceptionally(failure);
                    }
                    active.remove(key, marker);
                    if (failure == null) {
                        dailyBarStore.marketDay(clock.previousTradingDay(scanDate))
                                .filter(day -> day.status() == FirstBoardMarketDayStatus.READY)
                                .ifPresent(day -> startBuild(scanDate, day));
                    }
                });
    }

    private void startBuild(LocalDate scanDate, FirstBoardMarketDay marketDay) {
        String key = "build:" + scanDate + ":" + marketDay.barDataVersion();
        CompletableFuture<Void> marker = new CompletableFuture<>();
        if (active.putIfAbsent(key, marker) != null) {
            return;
        }
        CompletableFuture.runAsync(() -> build(scanDate, marketDay), coordinator)
                .whenComplete((none, failure) -> {
                    if (failure == null) {
                        marker.complete(null);
                    } else {
                        marker.completeExceptionally(failure);
                    }
                    active.remove(key, marker);
                });
    }

    private void build(LocalDate scanDate, FirstBoardMarketDay marketDay) {
        LocalDate baselineDate = marketDay.tradeDate();
        if (baselineStore.findReady(
                scanDate, baselineDate, BASELINE_RULE_VERSION, marketDay.barDataVersion()).isPresent()) {
            return;
        }
        AshareQuoteSnapshot quoteSnapshot = gateway.fullMarketQuotes();
        if (quoteSnapshot == null || !quoteSnapshot.complete()) {
            throw new IllegalStateException("全市场股票列表不完整");
        }
        List<EastMoneyQuote> supported = quoteSnapshot.quotes().stream()
                .filter(quote -> quote != null && quote.symbol() != null)
                .filter(quote -> FirstBoardBoard.fromSymbol(quote.symbol()) != FirstBoardBoard.UNSUPPORTED)
                .toList();
        AtomicInteger excludedNameCount = new AtomicInteger();
        Map<String, EastMoneyQuote> eligibleBySymbol = new LinkedHashMap<>();
        for (EastMoneyQuote quote : supported) {
            if (!eligibleName(quote.name())) {
                excludedNameCount.incrementAndGet();
            } else {
                eligibleBySymbol.putIfAbsent(quote.symbol(), quote);
            }
        }
        Map<String, Integer> curveCounts = dailyBarStore.completedBarCounts(
                eligibleBySymbol.keySet(), baselineDate, FirstBoardCurvePolicy.MINIMUM_VALID_BARS);
        eligibleBySymbol.entrySet().removeIf(entry ->
                !FirstBoardCurvePolicy.hasValidCurve(entry.getKey(), curveCounts));
        if (eligibleBySymbol.isEmpty()) {
            throw new IllegalStateException("没有股票达到最近至少 15 根有效日线门槛");
        }
        Set<String> previousLimitUps = dailyBarStore.confirmedLimitUpSymbols(baselineDate);
        AtomicInteger insufficientHistoryCount = new AtomicInteger();
        Map<String, FirstBoardBaseline> evaluated = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> tasks = eligibleBySymbol.values().stream()
                .map(quote -> CompletableFuture.runAsync(() -> {
                    try {
                        var rows = dailyBarStore.recentBars(quote.symbol(), baselineDate, 260);
                        evaluator.evaluate(
                                quote.symbol(), quote.name(), quote.industry(),
                                FirstBoardBoard.fromSymbol(quote.symbol()), rows, baselineDate,
                                previousLimitUps.contains(quote.symbol())
                        ).ifPresentOrElse(
                                baseline -> evaluated.put(quote.symbol(), baseline),
                                insufficientHistoryCount::incrementAndGet);
                    } catch (RuntimeException exception) {
                        insufficientHistoryCount.incrementAndGet();
                    }
                }, evaluationExecutor))
                .toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        if (!eligibleBySymbol.isEmpty() && evaluated.isEmpty()) {
            throw new IllegalStateException("本地日线无法生成任何首板技术基线");
        }

        List<FirstBoardBaseline> ordered = new ArrayList<>();
        eligibleBySymbol.keySet().stream().sorted().forEach(symbol -> {
            FirstBoardBaseline baseline = evaluated.get(symbol);
            if (baseline != null) {
                ordered.add(baseline);
            }
        });
        FirstBoardBaselineRun run = baselineStore.startRun(
                scanDate, baselineDate, BASELINE_RULE_VERSION, marketDay.barDataVersion(),
                quoteSnapshot.quotes().size(), eligibleBySymbol.size(), excludedNameCount.get(),
                insufficientHistoryCount.get());
        try {
            baselineStore.publish(run, ordered);
        } catch (RuntimeException exception) {
            baselineStore.fail(run, exception.getMessage());
            throw exception;
        }
    }

    private LocalDate effectiveScanDate(LocalDate requestedTradeDate) {
        LocalDate requested = requestedTradeDate == null ? clock.currentMarketDate() : requestedTradeDate;
        return clock.isMarketClosedDay(requested) ? clock.previousTradingDay(requested) : requested;
    }

    private boolean eligibleName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.toUpperCase(Locale.ROOT);
        return !normalized.contains("ST") && !normalized.contains("退");
    }

    @PreDestroy
    public void shutdown() {
        coordinator.shutdownNow();
        evaluationExecutor.shutdownNow();
    }
}

package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyLimitUpPoolEntry;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class FirstBoardMarketDayIngestionService {

    private static final int MINIMUM_HISTORY_BARS = 60;
    private static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final FirstBoardMarketDataGateway gateway;
    private final FirstBoardDailyBarStore store;
    private final TradingClockService clock;
    private final ExecutorService seedExecutor = Executors.newFixedThreadPool(12, runnable -> {
        Thread thread = new Thread(runnable, "first-board-seed");
        thread.setDaemon(true);
        return thread;
    });

    public FirstBoardMarketDayIngestionService(
            FirstBoardMarketDataGateway gateway,
            FirstBoardDailyBarStore store,
            TradingClockService clock
    ) {
        this.gateway = gateway;
        this.store = store;
        this.clock = clock;
    }

    public FirstBoardIngestionProgress ensureBaselineDayReady(LocalDate scanTradeDate) {
        LocalDate baselineDate = clock.previousTradingDay(scanTradeDate);
        FirstBoardMarketDay existing = store.marketDay(baselineDate).orElse(null);
        if (existing != null && existing.status() == FirstBoardMarketDayStatus.READY) {
            return FirstBoardIngestionProgress.from(existing);
        }
        if (existing != null && existing.status() == FirstBoardMarketDayStatus.FAILED
                && existing.nextRetryAt() != null && Instant.now().isBefore(existing.nextRetryAt())) {
            return FirstBoardIngestionProgress.from(existing);
        }

        Instant startedAt = existing == null || existing.startedAt() == null ? Instant.now() : existing.startedAt();
        try {
            if (existing == null && store.latestReadyMarketDay().isEmpty()) {
                store.importLatestHistoryVersions();
            }
            AshareQuoteSnapshot quoteSnapshot = gateway.fullMarketQuotes();
            if (quoteSnapshot == null || !quoteSnapshot.complete()) {
                throw new IllegalStateException("全市场股票列表不完整");
            }
            List<EastMoneyQuote> eligible = eligibleQuotes(quoteSnapshot);
            int total = eligible.size();
            saveImporting(baselineDate, startedAt, total, 0, 0);

            List<String> symbols = eligible.stream().map(EastMoneyQuote::symbol).toList();
            Map<String, Integer> counts = store.completedBarCounts(symbols, baselineDate, MINIMUM_HISTORY_BARS);
            List<EastMoneyQuote> gaps = eligible.stream()
                    .filter(quote -> counts.getOrDefault(quote.symbol(), 0) < MINIMUM_HISTORY_BARS)
                    .toList();
            AtomicInteger completed = new AtomicInteger(total - gaps.size());
            AtomicInteger failed = new AtomicInteger();
            List<CompletableFuture<Void>> tasks = gaps.stream()
                    .map(quote -> CompletableFuture.runAsync(() -> {
                        try {
                            List<EastMoneyKLine> rows = gateway.dailyBars(
                                    quote.symbol(), baselineDate.minusDays(260), baselineDate);
                            if (rows == null) {
                                throw new IllegalStateException("历史日线返回为空");
                            }
                            store.upsertBars(rows, "东方财富前复权日线");
                            completed.incrementAndGet();
                        } catch (RuntimeException exception) {
                            failed.incrementAndGet();
                        }
                        saveImporting(baselineDate, startedAt, total, completed.get(), failed.get());
                    }, seedExecutor))
                    .toList();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();

            Set<String> limitUps = gateway.limitUpPool(baselineDate).stream()
                    .map(EastMoneyLimitUpPoolEntry::symbol)
                    .filter(symbol -> symbol != null && !symbol.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
            store.confirmLimitUpPool(baselineDate, limitUps);
            int written = completed.get();
            if (failed.get() > 0 || written != total) {
                throw new IllegalStateException("历史日线补充失败: " + failed.get() + "/" + total);
            }
            FirstBoardMarketDay ready = new FirstBoardMarketDay(
                    baselineDate, baselineDate, FirstBoardMarketDayStatus.READY, total, written, 0, true,
                    store.calculateBarDataVersion(baselineDate), startedAt, Instant.now(), null, null);
            store.saveMarketDay(ready);
            return FirstBoardIngestionProgress.from(ready);
        } catch (RuntimeException exception) {
            return fail(baselineDate, startedAt, existing, exception);
        }
    }

    public FirstBoardIngestionProgress ingestClosedDay(LocalDate tradeDate) {
        FirstBoardMarketDay existing = store.marketDay(tradeDate).orElse(null);
        if (existing != null && existing.status() == FirstBoardMarketDayStatus.READY) {
            return FirstBoardIngestionProgress.from(existing);
        }
        Instant startedAt = existing == null || existing.startedAt() == null ? Instant.now() : existing.startedAt();
        if (tradeDate == null || clock.isMarketClosedDay(tradeDate) || !clock.isCompletedDailyBar(tradeDate)) {
            return fail(tradeDate, startedAt, existing,
                    new IllegalStateException("交易日尚未形成完整收盘日线"));
        }
        try {
            AshareQuoteSnapshot quoteSnapshot = gateway.fullMarketQuotes();
            if (quoteSnapshot == null || !quoteSnapshot.complete()) {
                throw new IllegalStateException("收盘全市场行情不完整");
            }
            List<EastMoneyQuote> eligible = eligibleQuotes(quoteSnapshot);
            if (eligible.stream().anyMatch(quote -> !tradeDate.equals(quote.tradeDate()))) {
                throw new IllegalStateException("收盘行情交易日不一致");
            }
            List<EastMoneyKLine> rows = eligible.stream().map(this::toDailyBar).filter(this::validBar).toList();
            if (rows.size() != eligible.size()) {
                throw new IllegalStateException("收盘行情存在无效 OHLCV");
            }
            saveImporting(tradeDate, startedAt, eligible.size(), 0, 0);
            store.upsertBars(rows, "东方财富收盘全市场行情");
            repairDiscontinuities(tradeDate, eligible);

            Set<String> limitUps = gateway.limitUpPool(tradeDate).stream()
                    .map(EastMoneyLimitUpPoolEntry::symbol)
                    .filter(symbol -> symbol != null && !symbol.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
            store.confirmLimitUpPool(tradeDate, limitUps);
            List<String> symbols = eligible.stream().map(EastMoneyQuote::symbol).toList();
            int written = store.closingPrices(symbols, tradeDate).size();
            if (written != symbols.size()) {
                throw new IllegalStateException("收盘日线写入不完整: " + written + "/" + symbols.size());
            }
            FirstBoardMarketDay ready = new FirstBoardMarketDay(
                    tradeDate, tradeDate, FirstBoardMarketDayStatus.READY, symbols.size(), written, 0, true,
                    store.calculateBarDataVersion(tradeDate), startedAt, Instant.now(), null, null);
            store.saveMarketDay(ready);
            return FirstBoardIngestionProgress.from(ready);
        } catch (RuntimeException exception) {
            return fail(tradeDate, startedAt, existing, exception);
        }
    }

    private void repairDiscontinuities(LocalDate tradeDate, List<EastMoneyQuote> quotes) {
        LocalDate previousDate = clock.previousTradingDay(tradeDate);
        List<String> symbols = quotes.stream().map(EastMoneyQuote::symbol).toList();
        Map<String, BigDecimal> previousCloses = store.closingPrices(symbols, previousDate);
        for (EastMoneyQuote quote : quotes) {
            BigDecimal storedClose = previousCloses.get(quote.symbol());
            if (storedClose == null || !positive(quote.latestPrice()) || quote.changePercent() == null) {
                continue;
            }
            BigDecimal denominator = BigDecimal.ONE.add(
                    quote.changePercent().divide(new BigDecimal("100"), MC), MC);
            if (denominator.signum() == 0) {
                continue;
            }
            BigDecimal impliedPreviousClose = quote.latestPrice().divide(denominator, MC);
            BigDecimal difference = impliedPreviousClose.subtract(storedClose, MC).abs()
                    .multiply(new BigDecimal("100"), MC).divide(storedClose, MC);
            if (difference.compareTo(new BigDecimal("0.50")) > 0) {
                List<EastMoneyKLine> repaired = gateway.dailyBars(
                        quote.symbol(), previousDate.minusDays(260), tradeDate);
                store.upsertBars(repaired, "东方财富前复权连续性修复");
                BigDecimal repairedPrevious = store.closingPrices(List.of(quote.symbol()), previousDate)
                        .get(quote.symbol());
                if (repairedPrevious == null || impliedPreviousClose.subtract(repairedPrevious, MC).abs()
                        .multiply(new BigDecimal("100"), MC).divide(repairedPrevious, MC)
                        .compareTo(new BigDecimal("0.50")) > 0) {
                    throw new IllegalStateException("前复权连续性修复失败: " + quote.symbol());
                }
            }
        }
    }

    private FirstBoardIngestionProgress fail(
            LocalDate tradeDate, Instant startedAt, FirstBoardMarketDay existing, RuntimeException exception
    ) {
        FirstBoardMarketDay current = tradeDate == null
                ? existing
                : store.marketDay(tradeDate).orElse(existing);
        if (current != null && current.status() == FirstBoardMarketDayStatus.READY) {
            return FirstBoardIngestionProgress.from(current);
        }
        String message = bounded(exception.getMessage());
        FirstBoardMarketDay failed = new FirstBoardMarketDay(
                tradeDate, tradeDate, FirstBoardMarketDayStatus.FAILED,
                current == null ? 0 : current.expectedSymbolCount(),
                current == null ? 0 : current.writtenSymbolCount(),
                Math.max(1, current == null ? 0 : current.failedSymbolCount()), false, null,
                startedAt == null ? Instant.now() : startedAt, null, Instant.now().plus(RETRY_DELAY), message);
        store.saveMarketDay(failed);
        return FirstBoardIngestionProgress.from(failed);
    }

    private void saveImporting(
            LocalDate tradeDate, Instant startedAt, int total, int completed, int failed
    ) {
        store.saveMarketDay(new FirstBoardMarketDay(
                tradeDate, tradeDate, FirstBoardMarketDayStatus.IMPORTING, total, completed, failed,
                false, null, startedAt, null, null, null));
    }

    private List<EastMoneyQuote> eligibleQuotes(AshareQuoteSnapshot snapshot) {
        Map<String, EastMoneyQuote> unique = new LinkedHashMap<>();
        snapshot.quotes().stream()
                .filter(quote -> quote != null && quote.symbol() != null)
                .filter(quote -> FirstBoardBoard.fromSymbol(quote.symbol()) != FirstBoardBoard.UNSUPPORTED)
                .filter(quote -> eligibleName(quote.name()))
                .forEach(quote -> unique.putIfAbsent(quote.symbol(), quote));
        return List.copyOf(unique.values());
    }

    private EastMoneyKLine toDailyBar(EastMoneyQuote quote) {
        return new EastMoneyKLine(
                quote.symbol(), quote.tradeDate(), quote.openPrice(), quote.latestPrice(), quote.highPrice(),
                quote.lowPrice(), quote.volume(), quote.amount(), quote.turnoverRate());
    }

    private boolean validBar(EastMoneyKLine row) {
        return row != null && positive(row.open()) && positive(row.close()) && positive(row.high())
                && positive(row.low()) && row.volume() != null && row.volume().signum() >= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean eligibleName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.toUpperCase(Locale.ROOT);
        return !normalized.contains("ST") && !normalized.contains("退");
    }

    private String bounded(String value) {
        String message = value == null || value.isBlank() ? "首板日线更新失败" : value;
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    @PreDestroy
    public void shutdown() {
        seedExecutor.shutdownNow();
    }
}

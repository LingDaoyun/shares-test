package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.TradingClockService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repairs only explicitly supplied observation members, never discovers a full-market universe. */
@Service
public class ShortTermDailyHistoryService {
    private static final int FAILURE_COOLDOWN_SECONDS = 120;
    private final EastMoneyClient client;
    private final FirstBoardDailyBarStore store;
    private final TradingClockService clock;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "short-term-history-repair");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<WindowKey, CompletableFuture<String>> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<WindowKey, Instant> failedUntil = new ConcurrentHashMap<>();

    public ShortTermDailyHistoryService(EastMoneyClient client, FirstBoardDailyBarStore store, TradingClockService clock) {
        this.client = client;
        this.store = store;
        this.clock = clock;
    }

    /** Whole-window QFQ replacement; returned gaps never imply failed rows have become valid. */
    public List<String> ensureWindow(Collection<String> symbols, LocalDate observationDate, int completedDays) {
        if (symbols == null || symbols.isEmpty()) return List.of();
        if (completedDays < 1 || completedDays > 120) throw new IllegalArgumentException("历史窗口须为1至120个完成交易日");
        List<LocalDate> dates = precedingDates(observationDate, completedDays);
        if (dates.size() != completedDays) return List.of("连续" + completedDays + "个已完成交易日未经过日历核实，未请求远程历史。");
        Instant now = now();
        failedUntil.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        List<CompletableFuture<String>> tasks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String symbol : new LinkedHashSet<>(symbols)) {
            if (symbol == null || !symbol.matches("\\d{6}")) {
                failures.add("无效证券代码，未请求历史");
                continue;
            }
            WindowKey key = new WindowKey(symbol, dates.get(dates.size() - 1), completedDays);
            try {
                if (exact(symbol, store.recentBars(symbol, key.end(), completedDays), dates)) continue;
            } catch (RuntimeException unavailable) {
                failures.add(symbol + "：本地历史读取失败，未发起远程覆盖");
                continue;
            }
            Instant retryAt = failedUntil.get(key);
            if (retryAt != null && retryAt.isAfter(now)) {
                failures.add(symbol + "：上次补齐失败，120秒冷却期内不重复请求");
                continue;
            }
            CompletableFuture<String> task = active.computeIfAbsent(key,
                    unused -> CompletableFuture.supplyAsync(() -> repair(key, dates), executor));
            task.whenComplete((result, failure) -> active.remove(key, task));
            tasks.add(task);
        }
        for (CompletableFuture<String> task : tasks) {
            try {
                String failure = task.join();
                if (failure != null) failures.add(failure);
            } catch (RuntimeException unavailable) {
                failures.add("历史补齐任务未完成，仍按缺口处理");
            }
        }
        List<String> gaps = new ArrayList<>();
        if (!failures.isEmpty()) gaps.add("连续" + completedDays + "日历史补齐未完成 " + failures.size()
                + "只；示例（最多5只）：" + String.join("；", failures.stream().limit(5).toList()));
        return List.copyOf(gaps);
    }

    private String repair(WindowKey key, List<LocalDate> dates) {
        try {
            // Another observation may have populated this symbol while this task was queued.
            List<EastMoneyKLine> local = store.recentBars(key.symbol(), key.end(), key.days());
            if (exact(key.symbol(), local, dates)) return null;
            List<EastMoneyKLine> fetched = client.fetchDailyKLines(key.symbol(), dates.get(0), key.end());
            if (!exact(key.symbol(), fetched, dates))
                throw new IllegalStateException("供应商未返回连续" + key.days() + "个同身份有效OHLCV交易日");
            List<EastMoneyKLine> ordered = fetched.stream().sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                    .map(row -> preserveCompatibleTradingMetrics(row, local)).toList();
            store.upsertBars(ordered, "短线观察：连续" + key.days() + "交易日前复权整窗回补");
            if (!exact(key.symbol(), store.recentBars(key.symbol(), key.end(), key.days()), dates))
                throw new IllegalStateException("写入后本地连续窗口仍未通过校验");
            failedUntil.remove(key);
            return null;
        } catch (RuntimeException unavailable) {
            failedUntil.put(key, now().plusSeconds(FAILURE_COOLDOWN_SECONDS));
            String reason = unavailable.getMessage() == null ? "历史来源暂不可用" : unavailable.getMessage();
            return key.symbol() + "：" + (reason.length() > 180 ? reason.substring(0, 180) : reason);
        }
    }

    private static EastMoneyKLine preserveCompatibleTradingMetrics(EastMoneyKLine fetched, List<EastMoneyKLine> local) {
        if (local == null || fetched.amount() != null && fetched.turnoverRate() != null) return fetched;
        for (EastMoneyKLine old : local) {
            if (old != null && fetched.symbol().equals(old.symbol()) && fetched.tradeDate().equals(old.tradeDate())
                    && same(fetched.open(), old.open()) && same(fetched.close(), old.close())
                    && same(fetched.high(), old.high()) && same(fetched.low(), old.low())
                    && same(fetched.volume(), old.volume())) {
                return new EastMoneyKLine(fetched.symbol(), fetched.tradeDate(), fetched.open(), fetched.close(),
                        fetched.high(), fetched.low(), fetched.volume(),
                        fetched.amount() != null ? fetched.amount() : old.amount(),
                        fetched.turnoverRate() != null ? fetched.turnoverRate() : old.turnoverRate());
            }
        }
        return fetched;
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private List<LocalDate> precedingDates(LocalDate observationDate, int count) {
        if (observationDate == null || observationDate.isAfter(clock.currentMarketDate())
                || !verifiedMarketDay(observationDate)) return List.of();
        List<LocalDate> dates = new ArrayList<>();
        LocalDate date = observationDate;
        for (int index = 0; index < count; index++) {
            date = clock.previousTradingDay(date);
            if (!verifiedMarketDay(date) || !clock.isCompletedDailyBar(date)) return List.of();
            dates.add(date);
        }
        Collections.reverse(dates);
        return List.copyOf(dates);
    }

    private boolean verifiedMarketDay(LocalDate date) {
        return clock.verifiedTradingDayAfter(date, 0).isPresent() && !clock.isMarketClosedDay(date);
    }

    private static boolean exact(String symbol, List<EastMoneyKLine> rows, List<LocalDate> dates) {
        if (rows == null || rows.size() != dates.size()) return false;
        Set<LocalDate> seen = new HashSet<>();
        for (EastMoneyKLine row : rows) {
            if (row == null || !symbol.equals(row.symbol()) || !dates.contains(row.tradeDate())
                    || !positive(row.open()) || !positive(row.close()) || !positive(row.high()) || !positive(row.low())
                    || !positive(row.volume()) || row.high().compareTo(row.open().max(row.close())) < 0
                    || row.low().compareTo(row.open().min(row.close())) > 0 || !seen.add(row.tradeDate())) return false;
        }
        return seen.containsAll(dates);
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private Instant now() { return clock.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant(); }
    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
    private record WindowKey(String symbol, LocalDate end, int days) { }
}

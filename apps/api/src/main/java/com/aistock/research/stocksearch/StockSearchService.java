package com.aistock.research.stocksearch;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class StockSearchService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DIRECTORY_CACHE_SECONDS = 6 * 3600;
    private final EastMoneyClient eastMoneyClient;
    private final SinaStockDirectoryClient directoryClient;
    private final Clock clock;
    private final Object cacheLock = new Object();
    private volatile CachedSnapshot cached;

    @Autowired
    public StockSearchService(EastMoneyClient eastMoneyClient, SinaStockDirectoryClient directoryClient) {
        this(eastMoneyClient, directoryClient, Clock.systemUTC());
    }

    StockSearchService(EastMoneyClient eastMoneyClient, SinaStockDirectoryClient directoryClient, Clock clock) {
        this.eastMoneyClient = eastMoneyClient;
        this.directoryClient = directoryClient;
        this.clock = clock;
    }

    public StockSearchResult search(String keyword, boolean refresh) {
        return search(keyword, refresh, StockSearchFilters.none());
    }

    public StockSearchResult search(String keyword, boolean refresh, StockSearchFilters filters) {
        String normalized = keyword == null ? "" : keyword.strip();
        if (normalized.length() > 80) throw new IllegalArgumentException("股票关键词不能超过80个字符");
        if (normalized.isEmpty() && !filters.hasConditions()) throw new IllegalArgumentException("请输入股票名称、代码关键词或设置筛选条件");
        StockDirectorySnapshot snapshot = directorySnapshot(refresh);
        StockKeywordMatcher keywordMatcher = new StockKeywordMatcher(normalized);
        List<StockDirectorySnapshot.Stock> matches = snapshot.stocks().stream()
                .filter(stock -> keywordMatcher.matches(stock.symbol(), stock.name()))
                .filter(filters::allows)
                .sorted(Comparator.comparing(StockDirectorySnapshot.Stock::symbol))
                .toList();
        Map<String, EastMoneyQuote> quotes = new LinkedHashMap<>();
        boolean quoteFailure = false;
        for (QuoteBatch batch : quoteBatches(matches)) {
            quoteFailure |= batch.failed();
            for (EastMoneyQuote quote : batch.quotes()) quotes.putIfAbsent(quote.symbol(), quote);
        }
        Instant fetchedAt = clock.instant();
        List<EastMoneyQuote> stocks = new ArrayList<>();
        int unverifiedCount = 0;
        for (StockDirectorySnapshot.Stock identity : matches) {
            EastMoneyQuote quote = withIdentity(identity, quotes.get(identity.symbol()), fetchedAt);
            StockSearchFilters.Match result = filters.evaluate(quote);
            if (result == StockSearchFilters.Match.MATCH) stocks.add(quote);
            else if (result == StockSearchFilters.Match.UNVERIFIED) unverifiedCount++;
        }
        long missingCount = matches.stream().filter(stock -> !quotes.containsKey(stock.symbol())).count();
        String source = snapshot.source() + " + 腾讯行情";
        if (quoteFailure) source += "（报价暂不可用）";
        if (missingCount > 0) source += "（报价缺失 " + missingCount + "）";
        return new StockSearchResult(normalized, List.copyOf(stocks), stocks.size(), matches.size(), unverifiedCount, snapshot.stocks().size(),
                snapshot.expectedCount(), snapshot.complete(), source, fetchedAt);
    }

    private List<QuoteBatch> quoteBatches(List<StockDirectorySnapshot.Stock> matches) {
        if (matches.isEmpty()) return List.of();
        int batchCount = (matches.size() + 59) / 60;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(6, batchCount));
        try {
            List<Future<QuoteBatch>> pending = new ArrayList<>();
            for (int start = 0; start < matches.size(); start += 60) {
                List<String> symbols = matches.subList(start, Math.min(start + 60, matches.size())).stream()
                        .map(StockDirectorySnapshot.Stock::symbol).toList();
                pending.add(executor.submit(() -> {
                    try {
                        return new QuoteBatch(eastMoneyClient.fetchTencentQuotes(symbols, Integer.MAX_VALUE), false);
                    } catch (CancellationException exception) {
                        throw exception;
                    } catch (IllegalStateException exception) {
                        return new QuoteBatch(List.of(), true);
                    }
                }));
            }
            List<QuoteBatch> batches = new ArrayList<>();
            for (Future<QuoteBatch> future : pending) batches.add(future.get());
            return batches;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("股票行情请求已取消");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IllegalStateException("股票行情获取失败", exception.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private StockDirectorySnapshot directorySnapshot(boolean refresh) {
        CachedSnapshot current = cached;
        if (!refresh && validCache(current)) {
            return current.snapshot();
        }
        synchronized (cacheLock) {
            current = cached;
            if (!refresh && validCache(current)) {
                return current.snapshot();
            }
            StockDirectorySnapshot snapshot = directoryClient.fetch();
            Instant now = clock.instant();
            cached = new CachedSnapshot(snapshot, now.plusSeconds(snapshot.complete() ? DIRECTORY_CACHE_SECONDS : 60),
                    now.atZone(MARKET_ZONE).toLocalDate());
            return snapshot;
        }
    }

    private boolean validCache(CachedSnapshot current) {
        Instant now = clock.instant();
        return current != null && now.isBefore(current.expiresAt())
                && current.date().equals(now.atZone(MARKET_ZONE).toLocalDate());
    }

    private EastMoneyQuote withIdentity(StockDirectorySnapshot.Stock stock, EastMoneyQuote quote, Instant fetchedAt) {
        if (quote == null) return new EastMoneyQuote(stock.symbol(), stock.name(), stock.market(), null,
                null, null, null, null, null, null, null, null, "腾讯行情缺失", null, fetchedAt);
        return new EastMoneyQuote(stock.symbol(), stock.name(), stock.market(), quote.industry(),
                quote.latestPrice(), quote.changePercent(), quote.turnoverRate(), quote.volume(), quote.amount(),
                quote.peRatio(), quote.pbRatio(), quote.peTtm(), quote.sourceName(), quote.quoteUrl(), quote.fetchedAt(),
                quote.tradeDate(), quote.marketTimestamp(), quote.totalMarketValue(), quote.openPrice(), quote.highPrice(),
                quote.lowPrice(), quote.realtimeVolumeRatio(), quote.circulatingMarketValue());
    }

    private record CachedSnapshot(StockDirectorySnapshot snapshot, Instant expiresAt, LocalDate date) {}
    private record QuoteBatch(List<EastMoneyQuote> quotes, boolean failed) {}
}

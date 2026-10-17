package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ShortTermUpperShadowService {
    public static final String RULE = "沪深主板非ST/退市股：近20个已完成交易日箱体振幅≤15%、首尾收盘变动绝对值≤5%；"
            + "当日上影占当日振幅≥50%、上影≥实体2倍且较昨收≥2%；最高价触及箱顶的98%以上；"
            + "上影相对长度≥前20日中位值2倍；成交额≥8000万。研究初始口径，非主力意图或买点判定。";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final EastMoneyClient client;
    private final FirstBoardDailyBarStore bars;
    private final TradingClockService clock;
    private final ShortTermDailyHistoryService history;
    private final ExecutorService industryExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "short-term-upper-shadow-industry");
        thread.setDaemon(true);
        return thread;
    });

    public ShortTermUpperShadowService(EastMoneyClient client, FirstBoardDailyBarStore bars, TradingClockService clock,
                                      ShortTermDailyHistoryService history) {
        this.client = client; this.bars = bars; this.clock = clock; this.history = history;
    }

    public ShortTermUpperShadowScan scan() {
        LocalDate date = clock.currentMarketDate();
        var snapshot = client.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);
        var now = clock.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
        Map<String, EastMoneyQuote> unique = new LinkedHashMap<>();
        for (var quote : snapshot.quotes()) {
            if (quote != null && quote.symbol() != null && quote.symbol().matches("\\d{6}")
                    && quote.name() != null && !quote.name().isBlank()) unique.putIfAbsent(quote.symbol(), quote);
        }
        if (!snapshot.complete() || snapshot.expectedCount() <= 0 || unique.size() != snapshot.expectedCount()
                || snapshot.quotes().size() != snapshot.expectedCount()) {
            return new ShortTermUpperShadowScan(date, now, "DATA_BLOCKED", snapshot.source(), snapshot.expectedCount(),
                    0, 0, 0, 0, 0, List.of(), List.of("原始证券清单的身份或全量覆盖未通过，不能形成完整形态观察。"), RULE);
        }
        // The rule's denominator is fixed by board/name identity, never by whether a price is available.
        List<EastMoneyQuote> scope = unique.values().stream().filter(this::supported).toList();
        int expectedCount = scope.size();
        String source = snapshot.source() + " · 原始证券清单 " + snapshot.expectedCount()
                + "；观察范围：沪深主板非ST/退市 " + expectedCount + " 只";
        List<EastMoneyQuote> fresh = freshQuotes(scope, date, now);
        if (expectedCount > 0 && fresh.size() < expectedCount * 0.95) {
            try {
                List<EastMoneyQuote> refreshed = client.fetchTencentQuotes(scope.stream().map(EastMoneyQuote::symbol).toList(), Integer.MAX_VALUE);
                Map<String, EastMoneyQuote> refreshedBySymbol = new LinkedHashMap<>();
                for (EastMoneyQuote quote : refreshed) {
                    if (quote == null || !unique.containsKey(quote.symbol()) || !supported(unique.get(quote.symbol()))
                            || refreshedBySymbol.putIfAbsent(quote.symbol(), quote) != null) {
                        throw new IllegalStateException("批量刷新返回了不属于已核实范围或重复的证券身份");
                    }
                }
                now = clock.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
                fresh = freshQuotes(new ArrayList<>(refreshedBySymbol.values()), date, now);
                source += " · 腾讯批量刷新（整组报价，未拼接旧价格）";
            } catch (RuntimeException unavailable) {
                return new ShortTermUpperShadowScan(date, now, "DATA_BLOCKED", source, expectedCount,
                        fresh.size(), 0, 0, 0, 0, List.of(), List.of("同日新鲜行情覆盖不足95%，批量刷新未完成：" + unavailable.getMessage()), RULE);
            }
        }
        if (expectedCount == 0 || fresh.size() < expectedCount * 0.95) {
            return new ShortTermUpperShadowScan(date, now, "DATA_BLOCKED", source, expectedCount,
                    fresh.size(), 0, 0, 0, 0, List.of(), List.of("沪深主板非ST/退市范围的同日新鲜行情覆盖不足95%，不能形成完整形态观察。"), RULE);
        }
        List<LocalDate> required = precedingDates(date);
        if (required.size() != 20) return new ShortTermUpperShadowScan(date, now, "DATA_BLOCKED", source,
                expectedCount, fresh.size(), 0, 0, 0, 0, List.of(), List.of("连续20个已完成交易日的日历未核实。"), RULE);
        List<ShortTermUpperShadowScan.Candidate> candidates = new ArrayList<>();
        int shaped = 0;
        int missing = 0;
        int inputMissing = 0;
        int supportedCount = 0;
        List<EastMoneyQuote> shapedQuotes = new ArrayList<>();
        for (var quote : fresh) {
            if (!supported(quote)) continue;
            supportedCount++;
            if (!shapeInputsAvailable(quote)) { inputMissing++; continue; }
            if (!shape(quote)) continue;
            shaped++;
            shapedQuotes.add(quote);
        }
        List<String> gaps = new ArrayList<>(history.ensureWindow(
                shapedQuotes.stream().map(EastMoneyQuote::symbol).toList(), date, 20));
        for (var quote : shapedQuotes) {
            try {
                List<EastMoneyKLine> history = bars.recentBars(quote.symbol(), required.get(19), 20);
                if (!validHistory(quote.symbol(), history, required) || !samePriceBasis(quote, history)) { missing++; continue; }
                evaluate(quote, history, required).ifPresent(candidates::add);
            } catch (RuntimeException unavailable) { missing++; }
        }
        candidates.sort(Comparator.comparing(ShortTermUpperShadowScan.Candidate::amount).reversed()
                .thenComparing(ShortTermUpperShadowScan.Candidate::symbol));
        if (supportedCount == 0) gaps.add("未取得可检查的沪深主板股票，无法判断形态候选。");
        if (missing > 0) gaps.add(missing + "只长上影形态股票缺少连续或同口径历史，未作横盘判断。");
        if (inputMissing > 0) gaps.add(inputMissing + "只支持范围股票的开高低、涨幅或量额数据无效，未判断形态。");
        if (fresh.size() < expectedCount) gaps.add((expectedCount - fresh.size()) + "只规则范围股票缺少同日新鲜报价，未检查形态。");
        String status = supportedCount == 0 || inputMissing == supportedCount ? "DATA_BLOCKED" : gaps.isEmpty() ? "READY" : "PARTIAL";
        List<CompletableFuture<ShortTermUpperShadowScan.Candidate>> enriched = candidates.stream()
                .map(candidate -> CompletableFuture.supplyAsync(
                        () -> enrichIndustry(candidate, unique.get(candidate.symbol())), industryExecutor)).toList();
        return new ShortTermUpperShadowScan(date, now, status, source,
                expectedCount, fresh.size(), shaped, missing, candidates.size(), 0,
                enriched.stream().map(CompletableFuture::join).toList(), gaps, RULE);
    }

    private ShortTermUpperShadowScan.Candidate enrichIndustry(ShortTermUpperShadowScan.Candidate candidate, EastMoneyQuote original) {
        String industry = industryName(candidate.industry());
        if (industry == null && original != null && candidate.symbol().equals(original.symbol())) {
            industry = industryName(original.industry());
        }
        if (industry == null) {
            try { industry = industryName(client.fetchStockBoardIndustry(candidate.symbol())); }
            catch (IllegalStateException unavailable) { /* Optional metadata must not discard a confirmed shape. */ }
        }
        if (industry == null) {
            try { industry = industryName(client.fetchCompanySurveyIndustry(candidate.symbol())); }
            catch (IllegalStateException unavailable) { /* Keep the industry unknown when both sources are unavailable. */ }
        }
        // Only identity metadata may come from the original list; every market field remains from this scan.
        return new ShortTermUpperShadowScan.Candidate(candidate.symbol(), candidate.name(), industry, candidate.label(),
                candidate.latestPrice(), candidate.changePercent(), candidate.amount(), candidate.turnoverRate(), candidate.marketTimestamp(),
                candidate.boxRangePercent(), candidate.boxNetChangePercent(), candidate.boxHigh(), candidate.boxLow(),
                candidate.upperShadowRatioPercent(), candidate.upperShadowPercent(), candidate.evidence(), candidate.counterEvidence());
    }

    private String industryName(String value) {
        return value == null || value.isBlank() || "-".equals(value.trim()) ? null : value.trim();
    }

    private List<EastMoneyQuote> freshQuotes(List<EastMoneyQuote> quotes, LocalDate date, java.time.Instant assessedAt) {
        if (!date.equals(assessedAt.atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate())) return List.of();
        var close = date.atTime(TradingClockService.REGULAR_CLOSE).atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
        boolean afterClose = !clock.isMarketClosedDay(date) && !assessedAt.isBefore(close);
        return quotes.stream().filter(quote -> date.equals(quote.tradeDate()) && positive(quote.latestPrice())
                && quote.marketTimestamp() != null && !quote.marketTimestamp().isAfter(assessedAt.plusSeconds(5))
                && date.equals(quote.marketTimestamp().atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalDate())
                && (afterClose ? !quote.marketTimestamp().isBefore(close)
                        : Duration.between(quote.marketTimestamp(), assessedAt).getSeconds() <= 120)).toList();
    }

    Optional<ShortTermUpperShadowScan.Candidate> evaluate(EastMoneyQuote quote, List<EastMoneyKLine> history, List<LocalDate> dates) {
        if (!shape(quote) || !validHistory(quote.symbol(), history, dates)) return Optional.empty();
        List<EastMoneyKLine> sorted = history.stream().sorted(Comparator.comparing(EastMoneyKLine::tradeDate)).toList();
        if (!samePriceBasis(quote, sorted)) return Optional.empty();
        BigDecimal previous = previousClose(quote);
        BigDecimal basisRatio = previous.divide(sorted.get(19).close(), 12, RoundingMode.HALF_UP);
        BigDecimal high = sorted.stream().map(EastMoneyKLine::high).max(Comparator.naturalOrder()).orElseThrow().multiply(basisRatio);
        BigDecimal low = sorted.stream().map(EastMoneyKLine::low).min(Comparator.naturalOrder()).orElseThrow().multiply(basisRatio);
        BigDecimal boxRange = percent(high.subtract(low), low);
        BigDecimal net = percent(sorted.get(19).close().subtract(sorted.get(0).close()), sorted.get(0).close());
        BigDecimal upper = upper(quote);
        BigDecimal upperPercent = percent(upper, previous);
        List<BigDecimal> priorShadows = sorted.stream()
                .map(row -> percent(row.high().subtract(row.open().max(row.close())), row.close()))
                .sorted().toList();
        BigDecimal medianUpper = priorShadows.get(9).add(priorShadows.get(10)).divide(new BigDecimal("2"));
        // QFQ history must agree with the quote's previous close before comparing the box with raw intraday prices.
        if (boxRange.compareTo(new BigDecimal("15")) > 0 || net.abs().compareTo(new BigDecimal("5")) > 0
                || quote.highPrice().compareTo(high.multiply(new BigDecimal("0.98"))) < 0
                || upperPercent.compareTo(medianUpper.multiply(new BigDecimal("2"))) < 0) return Optional.empty();
        boolean closingShape = quote.marketTimestamp() != null
                && !clock.currentMarketDateTime().toLocalTime().isBefore(TradingClockService.REGULAR_CLOSE)
                && !quote.marketTimestamp().atZone(TradingClockService.CHINA_MARKET_ZONE).toLocalTime().isBefore(TradingClockService.REGULAR_CLOSE);
        return Optional.of(new ShortTermUpperShadowScan.Candidate(quote.symbol(), quote.name(), quote.industry(),
                "横盘长上影观察", quote.latestPrice(), quote.changePercent(), quote.amount(), quote.turnoverRate(), quote.marketTimestamp(),
                boxRange, net, high, low, percent(upper, quote.highPrice().subtract(quote.lowPrice())), upperPercent,
                List.of("前20个连续已完成交易日箱体振幅 " + boxRange + "%，首尾收盘变动 " + net + "%。",
                        "当日高点触及箱顶附近后回落，当前上影相对昨收 " + upperPercent + "%，历史上影相对收盘中位值 " + medianUpper + "%，达到至少2倍。"),
                List.of("单根长上影无法确认主力试盘，也可能是抛压或冲高回落。",
                        closingShape ? "这是当日收盘报价形成的日K观察；后续是否承接需独立观察。"
                                : "这是扫描时点的未完成日K，收盘形态仍可能变化；后续是否承接需独立观察。")));
    }

    private List<LocalDate> precedingDates(LocalDate date) {
        List<LocalDate> result = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            date = clock.previousTradingDay(date);
            if (clock.verifiedTradingDayAfter(date, 0).isEmpty()) return List.of();
            result.add(0, date);
        }
        return result;
    }
    private boolean validHistory(String symbol, List<EastMoneyKLine> history, List<LocalDate> dates) {
        if (history == null || history.size() != 20 || dates.size() != 20) return false;
        Map<LocalDate, EastMoneyKLine> unique = new LinkedHashMap<>();
        for (var row : history) {
            if (!symbol.equals(row.symbol()) || !dates.contains(row.tradeDate()) || !positive(row.close()) || !positive(row.open())
                    || !positive(row.high()) || !positive(row.low()) || !positive(row.volume())
                    || row.high().compareTo(row.open().max(row.close())) < 0
                    || row.low().compareTo(row.open().min(row.close())) > 0
                    || unique.putIfAbsent(row.tradeDate(), row) != null) return false;
        }
        return unique.keySet().containsAll(dates);
    }
    private boolean supported(EastMoneyQuote quote) {
        String name = quote.name() == null ? "" : quote.name().toUpperCase();
        return quote.symbol() != null && (quote.symbol().startsWith("60") || quote.symbol().startsWith("00"))
                && !name.contains("ST") && !name.contains("退");
    }
    private boolean shape(EastMoneyQuote quote) {
        if (!positive(quote.openPrice()) || !positive(quote.highPrice()) || !positive(quote.lowPrice())
                || !positive(quote.latestPrice()) || !positive(quote.volume()) || quote.changePercent() == null
                || !positive(quote.amount()) || quote.amount().compareTo(new BigDecimal("80000000")) < 0
                || quote.highPrice().compareTo(quote.openPrice().max(quote.latestPrice())) < 0
                || quote.lowPrice().compareTo(quote.openPrice().min(quote.latestPrice())) > 0) return false;
        BigDecimal range = quote.highPrice().subtract(quote.lowPrice());
        BigDecimal upper = upper(quote);
        BigDecimal previous = previousClose(quote);
        return positive(range) && previous != null && upper.multiply(new BigDecimal("2")).compareTo(range) >= 0
                && upper.compareTo(quote.latestPrice().subtract(quote.openPrice()).abs().multiply(new BigDecimal("2"))) >= 0
                && percent(upper, previous).compareTo(new BigDecimal("2")) >= 0;
    }
    private BigDecimal upper(EastMoneyQuote quote) { return quote.highPrice().subtract(quote.openPrice().max(quote.latestPrice())); }
    private boolean samePriceBasis(EastMoneyQuote quote, List<EastMoneyKLine> history) {
        BigDecimal previous = previousClose(quote);
        BigDecimal historical = history.stream().max(Comparator.comparing(EastMoneyKLine::tradeDate)).orElseThrow().close();
        // 0.02% accommodates the quote's rounded percentage; larger changes are not assumed to share a price basis.
        return previous != null && percent(previous.subtract(historical).abs(), previous).compareTo(new BigDecimal("0.02")) <= 0;
    }
    private boolean shapeInputsAvailable(EastMoneyQuote quote) {
        return positive(quote.openPrice()) && positive(quote.highPrice()) && positive(quote.lowPrice())
                && quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("-100")) > 0
                && quote.volume() != null && quote.volume().signum() >= 0 && quote.amount() != null && quote.amount().signum() >= 0
                && quote.highPrice().compareTo(quote.openPrice().max(quote.latestPrice())) >= 0
                && quote.lowPrice().compareTo(quote.openPrice().min(quote.latestPrice())) <= 0;
    }
    private BigDecimal previousClose(EastMoneyQuote quote) {
        BigDecimal denominator = HUNDRED.add(quote.changePercent());
        return denominator.signum() <= 0 ? null : quote.latestPrice().multiply(HUNDRED).divide(denominator, 8, RoundingMode.HALF_UP);
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static BigDecimal percent(BigDecimal value, BigDecimal base) { return value.multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP); }
    @PreDestroy
    public void shutdown() { industryExecutor.shutdownNow(); }
}

package com.aistock.research.market;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.universe.UniversalAshareScreener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 高股息看板服务：拉取全市场最新完整年报的股息率指标，筛选超过阈值的股票，
 * 叠加实时行情快照并聚合行业分布。
 *
 * <p>年报指标是主数据，获取失败时快照整体不可用；实时行情补充独立容错，
 * 失败只标记数据缺口，不影响股息率排名。全市场年报拉取较慢，按阈值做
 * 300 秒快照缓存，刷新即重取。
 */
@Service
public class HighDividendBoardService {

    private static final Logger logger = LoggerFactory.getLogger(HighDividendBoardService.class);
    private static final long SNAPSHOT_TTL_SECONDS = 300;
    private static final int WHOLE_MARKET_INDICATOR_LIMIT = 6000;
    private static final int FALLBACK_YEAR_ATTEMPTS = 2;
    private static final int INDUSTRY_STAT_LIMIT = 8;
    private static final String UNCLASSIFIED_INDUSTRY = "未分类";
    /** 数据源含新三板/长期停牌样本，股息率常出现 40% 以上的异常值，超过该上限视为脏数据剔除。 */
    private static final BigDecimal YIELD_SANITY_CEILING = new BigDecimal("0.30");
    private static final java.util.regex.Pattern CASH_DIVIDEND_PATTERN =
            java.util.regex.Pattern.compile("派([0-9]+(?:\\.[0-9]+)?)元");

    private final EastMoneyClient eastMoneyClient;
    private final Map<BigDecimal, CachedSnapshot> cache = new ConcurrentHashMap<>();

    private record CachedSnapshot(HighDividendBoardSnapshot snapshot, Instant expiresAt) {
    }

    public HighDividendBoardService(EastMoneyClient eastMoneyClient) {
        this.eastMoneyClient = eastMoneyClient;
    }

    public HighDividendBoardSnapshot snapshot(BigDecimal minYieldPercent) {
        BigDecimal thresholdYield = normalizeThreshold(minYieldPercent);
        Instant now = Instant.now();
        CachedSnapshot cached = cache.get(thresholdYield);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.snapshot();
        }
        HighDividendBoardSnapshot snapshot = fetchSnapshot(thresholdYield);
        cache.put(thresholdYield, new CachedSnapshot(snapshot, now.plusSeconds(SNAPSHOT_TTL_SECONDS)));
        return snapshot;
    }

    /** thresholdYield 必须是已归一化的小数形式（0.05 = 5%）。 */
    HighDividendBoardSnapshot fetchSnapshot(BigDecimal thresholdYield) {
        Instant generatedAt = Instant.now();
        int latestFullYear = UniversalAshareScreener.latestCompletedAnnualReportYear(LocalDate.now());
        Map<String, EastMoneyAnnualIndicator> indicators = null;
        int reportYear = latestFullYear;
        List<String> dataGaps = new ArrayList<>();
        for (int attempt = 0; attempt < FALLBACK_YEAR_ATTEMPTS; attempt++) {
            int year = latestFullYear - attempt;
            try {
                Map<String, EastMoneyAnnualIndicator> fetched = eastMoneyClient.fetchAnnualIndicators(year, WHOLE_MARKET_INDICATOR_LIMIT);
                if (fetched.isEmpty()) {
                    dataGaps.add(year + " 年年报指标返回空集合。");
                    continue;
                }
                indicators = fetched;
                reportYear = year;
                break;
            } catch (RuntimeException exception) {
                logger.warn("{} 年年报指标获取失败：{}", year, rootMessage(exception));
                dataGaps.add(year + " 年年报指标数据源获取失败：" + rootMessage(exception));
            }
        }
        if (indicators == null) {
            return HighDividendBoardSnapshot.unavailable(
                    latestFullYear,
                    generatedAt,
                    thresholdYield,
                    "东方财富年报指标获取失败，无法计算股息率筛选。"
            );
        }
        List<EastMoneyAnnualIndicator> mainlandListed = indicators.values().stream()
                .filter(indicator -> isMainlandListed(indicator.symbol()))
                .toList();
        List<EastMoneyAnnualIndicator> excessive = mainlandListed.stream()
                .filter(indicator -> indicator.dividendYield() != null)
                .filter(indicator -> indicator.dividendYield().compareTo(YIELD_SANITY_CEILING) > 0)
                .toList();
        if (!excessive.isEmpty()) {
            dataGaps.add("剔除 " + excessive.size() + " 只股息率超过 30% 的异常样本（多为长期停牌股的失真数据）。");
        }
        Map<Boolean, List<EastMoneyAnnualIndicator>> byPayoutSanity = mainlandListed.stream()
                .filter(indicator -> indicator.dividendYield() != null)
                .filter(indicator -> indicator.dividendYield().compareTo(thresholdYield) > 0)
                .filter(indicator -> indicator.dividendYield().compareTo(YIELD_SANITY_CEILING) <= 0)
                .collect(Collectors.partitioningBy(HighDividendBoardService::dividendExceedsProfit));
        List<EastMoneyAnnualIndicator> payoutExcessive = byPayoutSanity.get(true);
        if (!payoutExcessive.isEmpty()) {
            dataGaps.add("剔除 " + payoutExcessive.size() + " 只分红超过当年利润的样本（亏损仍分红或派息率超 100%）。");
        }
        List<EastMoneyAnnualIndicator> matched = byPayoutSanity.get(false).stream()
                .sorted(Comparator.comparing(EastMoneyAnnualIndicator::dividendYield).reversed())
                .toList();
        Map<String, EastMoneyQuote> quotes = fetchQuotesFailSoft(matched, dataGaps);
        List<HighDividendStock> stocks = buildStocks(matched, quotes);
        List<HighDividendIndustryStat> industryStats = industryStats(stocks);
        if (reportYear != latestFullYear) {
            dataGaps.add("最新完整年报年度(" + latestFullYear + ")数据缺失，已回退使用 " + reportYear + " 年年报。");
        }
        return new HighDividendBoardSnapshot(
                reportYear,
                generatedAt,
                true,
                null,
                thresholdYield,
                stocks.size(),
                industryStats,
                stocks,
                dataGaps
        );
    }

    private Map<String, EastMoneyQuote> fetchQuotesFailSoft(
            List<EastMoneyAnnualIndicator> matched,
            List<String> dataGaps
    ) {
        if (matched.isEmpty()) {
            return Map.of();
        }
        List<String> symbols = matched.stream().map(EastMoneyAnnualIndicator::symbol).toList();
        try {
            return eastMoneyClient.fetchEastMoneyQuotesBySymbols(symbols, symbols.size()).stream()
                    .collect(Collectors.toMap(EastMoneyQuote::symbol, quote -> quote, (left, right) -> left));
        } catch (RuntimeException exception) {
            logger.warn("高股息样本实时行情补充失败：{}", rootMessage(exception));
            dataGaps.add("实时行情补充失败，现价/涨跌幅/估值字段缺失：" + rootMessage(exception));
            return Map.of();
        }
    }

    static List<HighDividendStock> buildStocks(
            List<EastMoneyAnnualIndicator> matched,
            Map<String, EastMoneyQuote> quotes
    ) {
        List<HighDividendStock> stocks = new ArrayList<>();
        for (int index = 0; index < matched.size(); index++) {
            EastMoneyAnnualIndicator indicator = matched.get(index);
            EastMoneyQuote quote = quotes.get(indicator.symbol());
            stocks.add(new HighDividendStock(
                    index + 1,
                    indicator.symbol(),
                    quote != null && quote.name() != null ? quote.name() : indicator.name(),
                    quote == null ? null : quote.market(),
                    quote == null || quote.industry() == null || quote.industry().isBlank()
                            ? UNCLASSIFIED_INDUSTRY
                            : quote.industry(),
                    indicator.dividendYield(),
                    dividendPayoutRatio(indicator),
                    quote == null ? null : quote.latestPrice(),
                    quote == null ? null : quote.changePercent(),
                    quote == null ? null : quote.amount(),
                    quote == null ? null : quote.peTtm(),
                    quote == null ? null : quote.pbRatio(),
                    indicator.roe(),
                    normalizeDescription(indicator.dividendPlanDescription())
            ));
        }
        return stocks;
    }

    static List<HighDividendIndustryStat> industryStats(List<HighDividendStock> stocks) {
        Map<String, List<HighDividendStock>> byIndustry = stocks.stream()
                .collect(Collectors.groupingBy(
                        stock -> stock.industry() == null || stock.industry().isBlank() ? UNCLASSIFIED_INDUSTRY : stock.industry(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        return byIndustry.values().stream()
                .map(group -> new HighDividendIndustryStat(
                        group.get(0).industry(),
                        group.size(),
                        group.stream().map(HighDividendStock::dividendYield).max(Comparator.naturalOrder()).orElse(null)
                ))
                .sorted(Comparator.comparing(HighDividendIndustryStat::stockCount).reversed())
                .limit(INDUSTRY_STAT_LIMIT)
                .toList();
    }

    /** 只保留沪深 A 股（代码 0/3/6 开头）；北交所与新三板样本停牌多、股息率口径失真，不纳入本看板。 */
    static boolean isMainlandListed(String symbol) {
        return symbol != null && (symbol.startsWith("0") || symbol.startsWith("3") || symbol.startsWith("6"));
    }

    /** 从分红方案文本（如“10派2.5元(含税)”）解析每股现金分红，解析失败返回 null。 */
    static BigDecimal cashDividendPerShare(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = CASH_DIVIDEND_PATTERN.matcher(description);
        if (!matcher.find()) {
            return null;
        }
        return new BigDecimal(matcher.group(1)).divide(BigDecimal.TEN, 4, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 分红超过当年利润的风险判定：每股分红 / 每股收益 &gt; 100%，或收益为负仍现金分红。
     * 分红方案或每股收益缺失时无法判定，返回 false（保留样本，派息率展示为待核）。
     */
    static boolean dividendExceedsProfit(EastMoneyAnnualIndicator indicator) {
        BigDecimal perShare = cashDividendPerShare(indicator.dividendPlanDescription());
        BigDecimal eps = indicator.eps();
        if (perShare == null || eps == null) {
            return false;
        }
        if (eps.compareTo(BigDecimal.ZERO) <= 0) {
            return perShare.compareTo(BigDecimal.ZERO) > 0;
        }
        return perShare.divide(eps, 6, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal.ONE) > 0;
    }

    /** 派息率 = 每股现金分红 / 每股收益（同一份年报口径），无法计算时返回 null。 */
    static BigDecimal dividendPayoutRatio(EastMoneyAnnualIndicator indicator) {
        BigDecimal perShare = cashDividendPerShare(indicator.dividendPlanDescription());
        BigDecimal eps = indicator.eps();
        if (perShare == null || eps == null || eps.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return perShare.divide(eps, 4, java.math.RoundingMode.HALF_UP);
    }

    static BigDecimal normalizeThreshold(BigDecimal minYieldPercent) {
        if (minYieldPercent == null) {
            return new BigDecimal("0.05");
        }
        BigDecimal clamped = minYieldPercent.max(new BigDecimal("0.5")).min(new BigDecimal("20"));
        return clamped.divide(new BigDecimal(100), 4, java.math.RoundingMode.HALF_UP);
    }

    static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}

package com.aistock.research.company;

import com.aistock.research.config.LiveDataProperties;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class CompanyService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyService.class);
    private static final long COMPANY_CACHE_SECONDS = 5;
    private final EastMoneyClient eastMoneyClient;
    private final LiveDataProperties properties;
    private final Object cacheLock = new Object();
    private volatile List<CompanyProfile> cachedProfiles = List.of();
    private volatile Instant cacheExpiresAt = Instant.EPOCH;

    public CompanyService(EastMoneyClient eastMoneyClient, LiveDataProperties properties) {
        this.eastMoneyClient = eastMoneyClient;
        this.properties = properties;
    }

    public List<CompanyProfile> listCompanies() {
        Instant now = Instant.now();
        if (now.isBefore(cacheExpiresAt) && !cachedProfiles.isEmpty()) {
            return cachedProfiles;
        }
        synchronized (cacheLock) {
            now = Instant.now();
            if (now.isBefore(cacheExpiresAt) && !cachedProfiles.isEmpty()) {
                return cachedProfiles;
            }
            return refreshCompanies(now);
        }
    }

    private List<CompanyProfile> refreshCompanies(Instant now) {
        try {
            Map<String, EastMoneyAnnualIndicator> indicators = shouldUseFastCompanyList() ? Map.of() : resolveAnnualIndicators();
            List<EastMoneyQuote> quotes = fetchQuotesSafely(indicators);
            List<CompanyProfile> profiles = quotes.stream()
                    .map(quote -> toProfile(quote, indicators.get(quote.symbol())))
                    .sorted(Comparator.comparing(CompanyProfile::themeRelevance).reversed()
                            .thenComparing(profile -> nullToZero(profile.amount()), Comparator.reverseOrder()))
                    .toList();
            profiles = balanceProfilesByMarket(profiles, properties.stockLimit());
            cachedProfiles = profiles;
            cacheExpiresAt = now.plusSeconds(COMPANY_CACHE_SECONDS);
            return profiles;
        } catch (IllegalStateException exception) {
            logger.warn("实时公司池刷新失败，本次不使用缓存或示例数据", exception);
            throw new IllegalStateException("实时公司池加载失败，本次未使用缓存或示例数据：" + rootMessage(exception), exception);
        }
    }

    private boolean shouldUseFastCompanyList() {
        return !Boolean.FALSE.equals(properties.fastCompanyList());
    }

    private Map<String, EastMoneyAnnualIndicator> resolveAnnualIndicators() {
        int latestFullYear = LocalDate.now().minusYears(1).getYear();
        int candidateLimit = Math.max(properties.stockLimit() * 8, properties.stockLimit());
        for (int yearOffset = 0; yearOffset < 2; yearOffset++) {
            int year = latestFullYear - yearOffset;
            Map<String, EastMoneyAnnualIndicator> indicators = fetchAnnualIndicatorsSafely(year, candidateLimit);
            if (!indicators.isEmpty()) {
                logger.info("已加载 {} 年度年报指标 {} 条", year, indicators.size());
                return indicators;
            }
        }
        logger.warn("最近两个完整年度均未获取到年报指标，当前公司池将退化为实时行情初筛");
        return Map.of();
    }

    private Map<String, EastMoneyAnnualIndicator> fetchAnnualIndicatorsSafely(int annualYear, int limit) {
        try {
            return eastMoneyClient.fetchAnnualIndicators(annualYear, limit);
        } catch (IllegalStateException exception) {
            logger.warn("年度财务指标获取失败，继续使用实时行情生成公司池", exception);
            return Map.of();
        }
    }

    private List<EastMoneyQuote> fetchQuotesSafely(Map<String, EastMoneyAnnualIndicator> indicators) {
        List<String> candidateSymbols = candidateSymbols(indicators);
        if (!candidateSymbols.isEmpty()) {
            try {
                return enrichQuotesWithEastMoney(eastMoneyClient.fetchTencentQuotes(candidateSymbols, properties.stockLimit()));
            } catch (IllegalStateException exception) {
                logger.warn("腾讯行情获取失败，切换东方财富行情备用源", exception);
            }
        } else {
            try {
                logger.warn("年度指标候选池为空，直接使用全 A 动态流动性行情池");
                return eastMoneyClient.fetchLiquidAshareQuotes(properties.stockLimit());
            } catch (IllegalStateException exception) {
                logger.warn("全 A 动态流动性行情池获取失败", exception);
                throw exception;
            }
        }
        try {
            return eastMoneyClient.fetchLiquidAshareQuotes(properties.stockLimit());
        } catch (IllegalStateException exception) {
            logger.warn("东方财富行情备用源获取失败", exception);
            throw exception;
        }
    }

    private List<EastMoneyQuote> enrichQuotesWithEastMoney(List<EastMoneyQuote> quotes) {
        if (quotes.isEmpty()) {
            return quotes;
        }
        try {
            Map<String, EastMoneyQuote> eastMoneyQuotes = eastMoneyClient.fetchEastMoneyQuotesBySymbols(
                            quotes.stream().map(EastMoneyQuote::symbol).toList(),
                            quotes.size()
                    ).stream()
                    .collect(Collectors.toMap(EastMoneyQuote::symbol, quote -> quote, (left, right) -> left));
            if (eastMoneyQuotes.isEmpty()) {
                return quotes;
            }
            return quotes.stream()
                    .map(quote -> mergeQuote(quote, eastMoneyQuotes.get(quote.symbol())))
                    .toList();
        } catch (IllegalStateException exception) {
            logger.warn("东方财富行业增强失败，继续使用原行情字段：{}", exception.getMessage());
            return quotes;
        }
    }

    private EastMoneyQuote mergeQuote(EastMoneyQuote original, EastMoneyQuote enriched) {
        if (enriched == null) {
            return original;
        }
        return new EastMoneyQuote(
                original.symbol(),
                valueOrDefault(enriched.name(), original.name()),
                valueOrDefault(enriched.market(), original.market()),
                valueOrDefault(enriched.industry(), original.industry()),
                decimalOrDefault(enriched.latestPrice(), original.latestPrice()),
                decimalOrDefault(enriched.changePercent(), original.changePercent()),
                decimalOrDefault(enriched.turnoverRate(), original.turnoverRate()),
                decimalOrDefault(enriched.volume(), original.volume()),
                decimalOrDefault(enriched.amount(), original.amount()),
                decimalOrDefault(enriched.peRatio(), original.peRatio()),
                decimalOrDefault(enriched.pbRatio(), original.pbRatio()),
                decimalOrDefault(enriched.peTtm(), original.peTtm()),
                original.sourceName() + " + 东方财富行业",
                valueOrDefault(enriched.quoteUrl(), original.quoteUrl()),
                original.fetchedAt(),
                original.tradeDate(),
                original.marketTimestamp()
        );
    }

    private List<String> candidateSymbols(Map<String, EastMoneyAnnualIndicator> indicators) {
        List<EastMoneyAnnualIndicator> ranked = indicators.values().stream()
                .filter(indicator -> indicator.symbol().startsWith("0")
                        || indicator.symbol().startsWith("3")
                        || indicator.symbol().startsWith("6")
                        || indicator.symbol().startsWith("4")
                        || indicator.symbol().startsWith("8")
                        || indicator.symbol().startsWith("9"))
                .sorted(Comparator.comparing(this::qualityScore).reversed())
                .toList();
        int target = Math.max(properties.stockLimit() * 8, properties.stockLimit());
        return balancedSymbols(ranked, target);
    }

    private List<String> balancedSymbols(List<EastMoneyAnnualIndicator> ranked, int target) {
        List<String> shMain = symbolsBy(ranked, symbol -> symbol.startsWith("60") || symbol.startsWith("601") || symbol.startsWith("603") || symbol.startsWith("605"));
        List<String> star = symbolsBy(ranked, symbol -> symbol.startsWith("688"));
        List<String> szMain = symbolsBy(ranked, symbol -> symbol.startsWith("00") || symbol.startsWith("001") || symbol.startsWith("002") || symbol.startsWith("003"));
        List<String> chinext = symbolsBy(ranked, symbol -> symbol.startsWith("30"));
        List<String> buckets = roundRobin(target, List.of(shMain, star, szMain, chinext));
        if (buckets.size() >= target) {
            return buckets;
        }
        Set<String> merged = new LinkedHashSet<>(buckets);
        ranked.stream().map(EastMoneyAnnualIndicator::symbol).forEach(merged::add);
        return merged.stream().limit(target).toList();
    }

    private List<String> symbolsBy(List<EastMoneyAnnualIndicator> ranked, Predicate<String> predicate) {
        return ranked.stream()
                .map(EastMoneyAnnualIndicator::symbol)
                .filter(predicate)
                .toList();
    }

    private List<String> roundRobin(int target, List<List<String>> buckets) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        boolean added;
        do {
            added = false;
            for (List<String> bucket : buckets) {
                if (index < bucket.size() && seen.add(bucket.get(index))) {
                    result.add(bucket.get(index));
                    added = true;
                    if (result.size() >= target) {
                        return result;
                    }
                }
            }
            index++;
        } while (added);
        return result;
    }

    private List<CompanyProfile> balanceProfilesByMarket(List<CompanyProfile> profiles, int limit) {
        if (profiles.stream().map(CompanyProfile::market).collect(Collectors.toSet()).size() <= 1) {
            return profiles;
        }
        List<CompanyProfile> sh = profiles.stream().filter(profile -> "上交所".equals(profile.market())).toList();
        List<CompanyProfile> sz = profiles.stream().filter(profile -> "深交所".equals(profile.market())).toList();
        List<CompanyProfile> bj = profiles.stream().filter(profile -> "北交所".equals(profile.market())).toList();
        List<CompanyProfile> balanced = roundRobinProfiles(limit, List.of(sh, sz, bj));
        if (balanced.size() >= Math.min(limit, profiles.size())) {
            return balanced;
        }
        Set<String> symbols = balanced.stream().map(CompanyProfile::symbol).collect(Collectors.toCollection(LinkedHashSet::new));
        List<CompanyProfile> merged = new ArrayList<>(balanced);
        for (CompanyProfile profile : profiles) {
            if (symbols.add(profile.symbol())) {
                merged.add(profile);
            }
            if (merged.size() >= limit) {
                break;
            }
        }
        return merged;
    }

    private List<CompanyProfile> roundRobinProfiles(int target, List<List<CompanyProfile>> buckets) {
        List<CompanyProfile> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        boolean added;
        do {
            added = false;
            for (List<CompanyProfile> bucket : buckets) {
                if (index < bucket.size() && seen.add(bucket.get(index).symbol())) {
                    result.add(bucket.get(index));
                    added = true;
                    if (result.size() >= target) {
                        return result;
                    }
                }
            }
            index++;
        } while (added);
        return result;
    }

    private BigDecimal qualityScore(EastMoneyAnnualIndicator indicator) {
        return nullToZero(indicator.roe()).multiply(BigDecimal.valueOf(100))
                .add(nullToZero(indicator.revenueGrowth()).multiply(BigDecimal.valueOf(50)))
                .add(nullToZero(indicator.netProfitGrowth()).multiply(BigDecimal.valueOf(20)))
                .add(nullToZero(indicator.eps()).multiply(BigDecimal.valueOf(10)));
    }

    public CompanyProfile getCompany(String symbol) {
        return listCompanies().stream()
                .filter(profile -> profile.symbol().equalsIgnoreCase(symbol))
                .findFirst()
                .map(this::enrichSingleProfileIfNeeded)
                .orElseGet(() -> fetchSingleCompany(symbol));
    }

    private CompanyProfile enrichSingleProfileIfNeeded(CompanyProfile profile) {
        if (profile.industry() != null && !profile.industry().isBlank()) {
            return profile;
        }
        CompanyProfile profileWithSurveyIndustry = enrichProfileWithSurveyIndustry(profile);
        if (profileWithSurveyIndustry.industry() != null && !profileWithSurveyIndustry.industry().isBlank()) {
            return profileWithSurveyIndustry;
        }
        try {
            List<EastMoneyQuote> quotes = eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of(profile.symbol()), 1);
            if (quotes.isEmpty() || quotes.get(0).industry() == null || quotes.get(0).industry().isBlank()) {
                return profile;
            }
            return toProfile(quotes.get(0), latestAnnualIndicator(profile.symbol()));
        } catch (IllegalStateException exception) {
            logger.warn("单票行业补全失败，继续使用当前公司画像：{}，原因：{}", profile.symbol(), exception.getMessage());
            return profile;
        }
    }

    private CompanyProfile fetchSingleCompany(String symbol) {
        List<EastMoneyQuote> quotes;
        try {
            quotes = eastMoneyClient.fetchTencentQuotes(List.of(symbol), 1);
        } catch (IllegalStateException exception) {
            logger.warn("单票腾讯行情获取失败，尝试东方财富行情：{}", symbol, exception);
            quotes = eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of(symbol), 1);
        }
        if (quotes.isEmpty()) {
            quotes = eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of(symbol), 1);
        }
        if (quotes.isEmpty()) {
            throw new IllegalArgumentException("未找到公司：" + symbol);
        }
        EastMoneyAnnualIndicator indicator = latestAnnualIndicator(symbol);
        EastMoneyQuote quote = quotes.get(0);
        if (quote.industry() == null || quote.industry().isBlank()) {
            String surveyIndustry = surveyIndustry(symbol);
            if (surveyIndustry != null) {
                quote = quoteWithIndustry(quote, surveyIndustry, quote.sourceName() + " + 东方财富行业");
            }
        }
        return toProfile(quote, indicator);
    }

    private EastMoneyAnnualIndicator latestAnnualIndicator(String symbol) {
        try {
            return eastMoneyClient.fetchAnnualIndicatorHistory(symbol, 1).stream()
                    .findFirst()
                    .orElse(null);
        } catch (IllegalStateException exception) {
            logger.warn("单票年报指标获取失败，继续仅使用行情画像：{}", symbol, exception);
            return null;
        }
    }

    private CompanyProfile enrichProfileWithSurveyIndustry(CompanyProfile profile) {
        String industry = surveyIndustry(profile.symbol());
        if (industry == null) {
            return profile;
        }
        return new CompanyProfile(
                profile.symbol(),
                profile.name(),
                profile.market(),
                industry,
                profile.themeCode(),
                profile.themeRelevance(),
                profile.latestPrice(),
                profile.changePercent(),
                profile.peTtm(),
                profile.pbRatio(),
                profile.turnoverRate(),
                profile.amount(),
                profile.quoteUrl(),
                profile.dataSource() + " + 东方财富行业",
                profile.fetchedAt(),
                profile.financialReportDate(),
                profile.financialDataType(),
                profile.liveData(),
                profile.coreAssets(),
                profile.risks(),
                profile.factors(),
                profile.evidence()
        );
    }

    private String surveyIndustry(String symbol) {
        try {
            String stockBoardIndustry = eastMoneyClient.fetchStockBoardIndustry(symbol);
            if (stockBoardIndustry != null) {
                return stockBoardIndustry;
            }
        } catch (IllegalStateException exception) {
            logger.warn("东方财富单票板块行业获取失败：{}，原因：{}", symbol, exception.getMessage());
        }
        try {
            return eastMoneyClient.fetchCompanySurveyIndustry(symbol);
        } catch (IllegalStateException exception) {
            logger.warn("东方财富 F10 行业获取失败：{}，原因：{}", symbol, exception.getMessage());
            return null;
        }
    }

    private EastMoneyQuote quoteWithIndustry(EastMoneyQuote quote, String industry, String sourceName) {
        return new EastMoneyQuote(
                quote.symbol(),
                quote.name(),
                quote.market(),
                industry,
                quote.latestPrice(),
                quote.changePercent(),
                quote.turnoverRate(),
                quote.volume(),
                quote.amount(),
                quote.peRatio(),
                quote.pbRatio(),
                quote.peTtm(),
                sourceName,
                quote.quoteUrl(),
                quote.fetchedAt(),
                quote.tradeDate(),
                quote.marketTimestamp()
        );
    }

    private CompanyProfile toProfile(EastMoneyQuote quote, EastMoneyAnnualIndicator indicator) {
        ThemeSelection theme = selectTheme(quote);
        Map<String, BigDecimal> factors = factors(quote, indicator, theme);
        List<String> assets = List.of(
                "行业归属：" + valueOrUnknown(quote.industry()),
                "成交额样本靠前，流动性便于持续跟踪",
                "主题关键词命中：" + String.join("、", theme.matchedKeywords())
        );
        List<String> risks = risks(quote, indicator);
        List<EvidenceItem> evidence = evidence(quote, indicator, theme);

        return new CompanyProfile(
                quote.symbol(),
                quote.name(),
                quote.market(),
                quote.industry(),
                theme.themeCode(),
                theme.relevanceScore(),
                quote.latestPrice(),
                quote.changePercent(),
                quote.peTtm(),
                quote.pbRatio(),
                quote.turnoverRate(),
                quote.amount(),
                quote.quoteUrl(),
                quote.sourceName() + " + 官方政策源 + 年报指标（如已匹配）",
                quote.fetchedAt().toString(),
                indicator == null ? null : indicator.reportDate(),
                indicator == null ? null : indicator.dataType(),
                true,
                assets,
                risks,
                factors,
                evidence
        );
    }

    private Map<String, BigDecimal> factors(EastMoneyQuote quote, EastMoneyAnnualIndicator indicator, ThemeSelection theme) {
        Map<String, BigDecimal> factors = new LinkedHashMap<>();
        put(factors, "roe_annual", indicator == null ? null : indicator.roe());
        put(factors, "operating_cash_flow_per_share", indicator == null ? null : indicator.operatingCashFlowPerShare());
        put(factors, "gross_margin", indicator == null ? null : indicator.grossMargin());
        put(factors, "revenue_growth", indicator == null ? null : indicator.revenueGrowth());
        put(factors, "net_profit_growth", indicator == null ? null : indicator.netProfitGrowth());
        put(factors, "eps", indicator == null ? null : indicator.eps());
        put(factors, "bps", indicator == null ? null : indicator.bps());
        put(factors, "pe_ttm", positiveOrNull(quote.peTtm()));
        put(factors, "pb", positiveOrNull(quote.pbRatio()));
        put(factors, "turnover_rate", quote.turnoverRate());
        put(factors, "amount", quote.amount());
        put(factors, "policy_theme_relevance", theme.relevanceScore().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        put(factors, "st_flag", quote.name().contains("ST") ? BigDecimal.ONE : BigDecimal.ZERO);
        return factors;
    }

    private List<EvidenceItem> evidence(EastMoneyQuote quote, EastMoneyAnnualIndicator indicator, ThemeSelection theme) {
        EvidenceItem quoteEvidence = new EvidenceItem(
                "实时行情",
                quote.name() + " " + quote.sourceName(),
                "最新价 " + valueOrUnknown(quote.latestPrice()) + "，PE(TTM) " + valueOrUnknown(quote.peTtm())
                        + "，PB " + valueOrUnknown(quote.pbRatio()) + "。",
                quote.quoteUrl(),
                82
        );
        EvidenceItem financeEvidence = new EvidenceItem(
                "年报指标",
                indicator == null ? "年报指标暂未匹配" : indicator.dataType(),
                indicator == null
                        ? "当前样本未匹配到最近年度年报指标，质量规则会要求人工复核。"
                        : "ROE " + percentText(indicator.roe()) + "，营收同比 " + percentText(indicator.revenueGrowth())
                        + "，每股经营现金流 " + valueOrUnknown(indicator.operatingCashFlowPerShare()) + "。",
                "https://data.eastmoney.com/stockdata/" + quote.symbol() + ".html",
                indicator == null ? 55 : 78
        );
        EvidenceItem themeEvidence = new EvidenceItem(
                "主题映射",
                theme.themeCode(),
                "基于公司名称与行业关键词命中：" + String.join("、", theme.matchedKeywords()) + "。",
                "https://www.gov.cn/zhengce/zuixin/",
                66
        );
        return List.of(quoteEvidence, financeEvidence, themeEvidence);
    }

    private List<String> risks(EastMoneyQuote quote, EastMoneyAnnualIndicator indicator) {
        java.util.ArrayList<String> risks = new java.util.ArrayList<>();
        risks.add("当前为公开行情与年报指标初筛，仍需公告和主营收入占比验证。");
        if (quote.name().contains("ST")) {
            risks.add("名称包含 ST，长线池需优先排除或人工复核。");
        }
        if (quote.peTtm() == null || quote.peTtm().compareTo(BigDecimal.ZERO) <= 0) {
            risks.add("PE(TTM) 为负或缺失，盈利稳定性需复核。");
        } else if (quote.peTtm().compareTo(new BigDecimal("80")) > 0) {
            risks.add("PE(TTM) 较高，可能已经透支部分成长预期。");
        }
        if (indicator == null) {
            risks.add("未匹配最近年度年报指标。");
        } else {
            if (indicator.roe() != null && indicator.roe().compareTo(BigDecimal.ZERO) < 0) {
                risks.add("最近年度 ROE 为负。");
            }
            if (indicator.revenueGrowth() != null && indicator.revenueGrowth().compareTo(BigDecimal.ZERO) < 0) {
                risks.add("最近年度营收同比下降。");
            }
        }
        return risks;
    }

    private ThemeSelection selectTheme(EastMoneyQuote quote) {
        String text = (quote.name() + " " + quote.industry()).toLowerCase();
        List<String> highEnd = List.of("制造", "设备", "机械", "机器人", "自动化", "软件", "元件", "半导体", "电子", "仪器", "新材料", "光学", "军工");
        List<String> digital = List.of("软件", "通信", "计算机", "互联网", "数据", "信息", "网络", "光模块", "电子", "半导体", "传媒");
        List<String> green = List.of("电力", "电池", "光伏", "风电", "能源", "环保", "储能", "电网", "汽车", "电源", "农业");

        ThemeSelection a = scoreTheme("NEW_QUALITY_PRODUCTIVITY", text, highEnd);
        ThemeSelection b = scoreTheme("DIGITAL_INFRA", text, digital);
        ThemeSelection c = scoreTheme("GREEN_TRANSITION", text, green);
        return List.of(a, b, c).stream()
                .sorted(Comparator.comparing(ThemeSelection::relevanceScore).reversed())
                .findFirst()
                .orElse(a);
    }

    private ThemeSelection scoreTheme(String themeCode, String text, List<String> keywords) {
        List<String> matched = keywords.stream()
                .filter(keyword -> text.contains(keyword.toLowerCase()))
                .distinct()
                .toList();
        BigDecimal score = BigDecimal.valueOf(48 + Math.min(matched.size() * 12L, 42));
        if (matched.isEmpty()) {
            matched = List.of("行业待复核");
        }
        return new ThemeSelection(themeCode, score, matched);
    }

    private void put(Map<String, BigDecimal> factors, String key, BigDecimal value) {
        if (value != null) {
            factors.put(key, value);
        }
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal decimalOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String valueOrUnknown(Object value) {
        return value == null ? "待补充" : value.toString();
    }

    private String percentText(BigDecimal value) {
        if (value == null) {
            return "待补充";
        }
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private record ThemeSelection(
            String themeCode,
            BigDecimal relevanceScore,
            List<String> matchedKeywords
    ) {
    }
}

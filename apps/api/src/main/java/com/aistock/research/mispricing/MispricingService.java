package com.aistock.research.mispricing;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.EvidenceCompletenessInput;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.quality.RecommendationEvidenceBundle;
import com.aistock.research.quality.RecommendationEvidenceEnrichmentService;
import com.aistock.research.quality.RecommendationQuality;
import com.aistock.research.tech.TechTrackedStock;
import com.aistock.research.tech.TechTrackingReport;
import com.aistock.research.tech.TechTrackingService;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.universe.UniversalAshareScreener;
import com.aistock.research.universe.UniversalScreenCandidate;
import com.aistock.research.universe.UniversalScreenReport;
import com.aistock.research.universe.UniversalScreenRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class MispricingService {

    private static final Logger logger = LoggerFactory.getLogger(MispricingService.class);
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_SCAN_LIMIT = 1200;
    private static final int MAX_SCAN_LIMIT = 5000;
    private static final int DYNAMIC_SCAN_TIMEOUT_SECONDS = 15;
    private static final String PRIMARY_QUOTE_NOTE = "核心种子行情优先来自腾讯批量接口；盘前或休市时最新价会回落到上一交易日收盘价，涨跌幅可能显示为 0。";
    private static final String FALLBACK_QUOTE_NOTE = "腾讯批量行情暂不可用或部分缺失，已自动切换东方财富补充；请重点复核价格、PE、PB 和涨跌幅。";
    private static final String DEGRADED_QUOTE_NOTE = "实时行情源暂不可用，本轮仅返回内置公司池和规则复核状态；缺少价格、PE、PB 的标的不能作为执行买点。";
    private static final String DYNAMIC_SCAN_SOURCE_NOTE = "全 A 股动态池使用内置 A 股代码索引 + 腾讯批量行情优先，东方财富全市场行情作为补充。";
    private static final String FINANCIAL_HISTORY_GAP = "近三年点时财报尚未接入本轮错杀扫描，人工质量分和行业代理分不能作为买入证据。";
    private static final MispricingRuleSet DEFAULT_RULE_SET = new MispricingRuleSet(
            new BigDecimal("70"),
            new BigDecimal("18"),
            new BigDecimal("2.50"),
            new BigDecimal("78"),
            new BigDecimal("4.00"),
            new BigDecimal("7.00"),
            DEFAULT_SCAN_LIMIT
    );
    private static final BigDecimal MAX_ENTRY_RISE_PERCENT = new BigDecimal("1.20");
    private final EastMoneyClient eastMoneyClient;
    private final TechTrackingService techTrackingService;
    private final UniversalAshareScreener universalScreener;
    private final EvidenceCompletenessService evidenceCompletenessService;
    private final RecommendationEvidenceEnrichmentService evidenceEnrichmentService;

    public MispricingService(EastMoneyClient eastMoneyClient, TechTrackingService techTrackingService) {
        this(
                eastMoneyClient,
                techTrackingService,
                eastMoneyClient == null ? null : new UniversalAshareScreener(eastMoneyClient),
                new EvidenceCompletenessService(),
                new RecommendationEvidenceEnrichmentService()
        );
    }

    @Autowired
    public MispricingService(
            EastMoneyClient eastMoneyClient,
            TechTrackingService techTrackingService,
            UniversalAshareScreener universalScreener,
            EvidenceCompletenessService evidenceCompletenessService,
            RecommendationEvidenceEnrichmentService evidenceEnrichmentService
    ) {
        this.eastMoneyClient = eastMoneyClient;
        this.techTrackingService = techTrackingService;
        this.universalScreener = universalScreener;
        this.evidenceCompletenessService = evidenceCompletenessService;
        this.evidenceEnrichmentService = evidenceEnrichmentService;
    }

    public MispricingReport report(Integer limit, BigDecimal hotHeat, BigDecimal maxPe, BigDecimal maxPb, BigDecimal minQuality) {
        return report(limit, hotHeat, maxPe, maxPb, minQuality, null);
    }

    public MispricingReport report(
            Integer limit,
            BigDecimal hotHeat,
            BigDecimal maxPe,
            BigDecimal maxPb,
            BigDecimal minQuality,
            Integer scanLimit
    ) {
        MispricingRuleSet ruleSet = resolveRuleSet(maxPe, maxPb, minQuality, scanLimit);
        StyleHeatSnapshot styleHeat = resolveStyleHeat(hotHeat);
        CandidateUniverse universe = resolveCandidateUniverse(ruleSet);
        Map<String, EastMoneyQuote> quotes = universe.quotes();

        List<ScoredAsset> scored = universe.seeds().stream()
                .map(seed -> score(seed, quotes.get(seed.symbol()), styleHeat, ruleSet))
                .filter(item -> RecommendationQuality.hasSufficientLiquidity(item.asset().amount()))
                .sorted(Comparator.comparingInt((ScoredAsset item) -> actionPriority(item.asset().action())).reversed()
                        .thenComparing((ScoredAsset item) -> item.asset().score().finalScore(), Comparator.reverseOrder()))
                .toList();
        int safeLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, scored.size()));
        List<ScoredAsset> finalists = scored.stream()
                .limit(safeLimit * 3L)
                .filter(item -> !isLongSideways(item.asset().symbol()))
                .limit(safeLimit)
                .toList();
        List<MispricedAsset> candidates = IntStream.range(0, finalists.size())
                .mapToObj(index -> rerank(finalists.get(index).asset(), index + 1))
                .toList();

        return new MispricingReport(
                "全 A 股错杀估值池",
                universe.seeds().size(),
                candidates.size(),
                universe.quoteNote(),
                List.of(
                        "候选池 = 全 A 股实时行情 + 统一候选漏斗动态初筛，不注入人工股票种子。",
                        "长投低估值池和短线右侧分开：长投核心是财务质量、估值折价和安全边际，不要求右侧启动。",
                        "热门方向过热只作为风险背景，不再直接推导买入；真正触发必须同时通过质量、估值、价格行为和反证检查。",
                        "评分 = 热门过热 20% + 资产质量 25% + 估值折价 30% + 现金流防御 15% + 轮动时机 10%。",
                        "只有质量、估值、热门过热、弱势日价格和 8000 万以上成交额同时达标时，才进入核心错杀候选。",
                        "全市场动态候选的质量分是代理分，不等于财报结论；进入前排后仍需要公告、财报和多 Agent 共识复核。"
                ),
                styleHeat,
                ruleSet,
                policySignals(),
                candidates,
                Instant.now()
        );
    }

    private CandidateUniverse resolveCandidateUniverse(MispricingRuleSet ruleSet) {
        QuoteFetchResult coreQuoteFetch = new QuoteFetchResult(List.of(), "候选仅来自全 A 实时动态行情池，不注入任何静态股票白名单。");
        Map<String, EastMoneyQuote> quoteMap = coreQuoteFetch.quotes().stream()
                .collect(Collectors.toMap(EastMoneyQuote::symbol, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        LinkedHashMap<String, AssetSeed> seeds = new LinkedHashMap<>();
        String quoteNote = coreQuoteFetch.quoteNote();
        try {
            DynamicMergeResult universalMerge = mergeUniversalCandidates(ruleSet, seeds, quoteMap);
            quoteNote = quoteNote
                    + " " + DYNAMIC_SCAN_SOURCE_NOTE
                    + " 已通过统一全 A 候选漏斗扫描 " + universalMerge.scannedCount()
                    + " 条，新增动态错杀候选 " + universalMerge.dynamicCount()
                    + " 条。";
        } catch (RuntimeException exception) {
            logger.warn("统一全A候选漏斗暂不可用，回退旧动态错杀扫描：{}", exception.getMessage());
            try {
                DynamicMergeResult fallbackMerge = mergeLegacyDynamicCandidates(ruleSet, seeds, quoteMap);
                quoteNote = quoteNote
                        + " 统一全 A 候选漏斗暂不可用，已回退旧扫描：" + exception.getMessage()
                        + " " + DYNAMIC_SCAN_SOURCE_NOTE
                        + " 已从全 A 股行情池扫描 " + fallbackMerge.scannedCount()
                        + " 条，保留可交易普通股 " + fallbackMerge.tradableCount()
                        + " 条，新增动态错杀候选 " + fallbackMerge.dynamicCount()
                        + " 条。";
            } catch (RuntimeException fallbackException) {
                logger.warn("全A动态错杀候选扫描失败，本轮保持空池", fallbackException);
                quoteNote = quoteNote + " 全 A 股动态行情不可用，本轮返回空池且未使用静态股票白名单：" + fallbackException.getMessage();
            }
        }

        if (seeds.isEmpty()) {
            quoteNote = quoteNote + " 全 A 动态筛选未产生候选，本轮保持空池，未使用静态股票白名单。";
        }

        return new CandidateUniverse(new ArrayList<>(seeds.values()), quoteMap, quoteNote);
    }

    private DynamicMergeResult mergeUniversalCandidates(
            MispricingRuleSet ruleSet,
            LinkedHashMap<String, AssetSeed> seeds,
            Map<String, EastMoneyQuote> quoteMap
    ) {
        if (universalScreener == null) {
            throw new IllegalStateException("统一全 A 候选漏斗未初始化");
        }
        UniversalScreenReport report = universalScreener.screen(universalRequest(ruleSet));
        int dynamicCount = 0;
        for (UniversalScreenCandidate candidate : report.candidates()) {
            EastMoneyQuote quote = quoteFromUniversalCandidate(candidate);
            quoteMap.merge(
                    quote.symbol(),
                    quote,
                    (existing, incoming) -> mergeQuote(existing, incoming)
            );
            if (passesDynamicPreFilter(quote, ruleSet) && !seeds.containsKey(quote.symbol())) {
                seeds.put(quote.symbol(), dynamicSeed(quote, ruleSet));
                dynamicCount++;
            }
        }
        return new DynamicMergeResult(report.reviewedCount(), report.reviewedCount(), dynamicCount);
    }

    private DynamicMergeResult mergeLegacyDynamicCandidates(
            MispricingRuleSet ruleSet,
            LinkedHashMap<String, AssetSeed> seeds,
            Map<String, EastMoneyQuote> quoteMap
    ) {
            List<EastMoneyQuote> marketQuotes = fetchDynamicMarketQuotes(ruleSet.scanLimit());
            int tradableCount = 0;
            int dynamicCount = 0;
            for (EastMoneyQuote quote : marketQuotes) {
                if (!isTradableCommonShare(quote)) {
                    continue;
                }
                tradableCount++;
                quoteMap.merge(
                        quote.symbol(),
                        quote,
                        (existing, incoming) -> mergeQuote(existing, incoming)
                );
                if (passesDynamicPreFilter(quote, ruleSet) && !seeds.containsKey(quote.symbol())) {
                    seeds.put(quote.symbol(), dynamicSeed(quote, ruleSet));
                    dynamicCount++;
                }
            }
        return new DynamicMergeResult(marketQuotes.size(), tradableCount, dynamicCount);
    }

    private UniversalScreenRequest universalRequest(MispricingRuleSet ruleSet) {
        BigDecimal loosePeCeiling = ruleSet.maxPeForValue().multiply(new BigDecimal("1.80")).max(new BigDecimal("32"));
        BigDecimal loosePbCeiling = ruleSet.maxPbForValue().multiply(new BigDecimal("1.80")).max(new BigDecimal("4.50"));
        return new UniversalScreenRequest(
                Math.min(80, Math.max(DEFAULT_LIMIT, ruleSet.scanLimit())),
                ruleSet.scanLimit(),
                RecommendationQuality.MIN_RECOMMENDED_AMOUNT,
                loosePeCeiling,
                loosePbCeiling,
                new BigDecimal("45"),
                true,
                true,
                "VALUE"
        );
    }

    private EastMoneyQuote quoteFromUniversalCandidate(UniversalScreenCandidate candidate) {
        return new EastMoneyQuote(
                candidate.symbol(),
                candidate.name(),
                candidate.market(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.changePercent(),
                null,
                null,
                candidate.amount(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.peTtm(),
                candidate.sourceName(),
                candidate.quoteUrl(),
                candidate.fetchedAt(),
                candidate.tradeDate(),
                candidate.marketTimestamp()
        );
    }

    private List<EastMoneyQuote> fetchDynamicMarketQuotes(int scanLimit) {
        CompletableFuture<List<EastMoneyQuote>> future = CompletableFuture.supplyAsync(() -> eastMoneyClient.fetchAshareQuotes(scanLimit));
        try {
            return future.get(DYNAMIC_SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException(
                    "全 A 股动态扫描超过 " + DYNAMIC_SCAN_TIMEOUT_SECONDS + " 秒预算，已跳过本轮动态扩展",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("全 A 股动态扫描被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("全 A 股动态扫描失败", cause);
        }
    }

    private QuoteFetchResult fetchQuotes(List<String> symbols) {
        List<EastMoneyQuote> tencentQuotes = List.of();
        RuntimeException tencentFailure = null;
        try {
            tencentQuotes = eastMoneyClient.fetchTencentQuotes(symbols, symbols.size());
        } catch (RuntimeException exception) {
            tencentFailure = exception;
            logger.warn("腾讯批量行情获取失败，尝试东方财富补充：{}", exception.getMessage());
        }
        if (tencentQuotes.size() >= symbols.size() && tencentQuotes.stream().allMatch(this::hasUsablePerSharePrice)) {
            return new QuoteFetchResult(tencentQuotes, PRIMARY_QUOTE_NOTE);
        }

        List<String> missingSymbols = missingOrInvalidPriceSymbols(symbols, tencentQuotes);
        List<EastMoneyQuote> eastMoneyQuotes = List.of();
        RuntimeException eastMoneyFailure = null;
        try {
            eastMoneyQuotes = eastMoneyClient.fetchEastMoneyQuotesBySymbols(
                    missingSymbols.isEmpty() ? symbols : missingSymbols,
                    missingSymbols.isEmpty() ? symbols.size() : missingSymbols.size()
            );
        } catch (RuntimeException exception) {
            eastMoneyFailure = exception;
            logger.warn("东方财富补充行情获取失败：{}", exception.getMessage());
        }

        List<EastMoneyQuote> mergedQuotes = mergeQuotes(tencentQuotes, eastMoneyQuotes);
        if (!mergedQuotes.isEmpty()) {
            String quoteNote = tencentQuotes.isEmpty()
                    ? FALLBACK_QUOTE_NOTE
                    : PRIMARY_QUOTE_NOTE + " 东方财富仅用于补齐腾讯缺失的部分标的。";
            return new QuoteFetchResult(mergedQuotes, quoteNote);
        }

        if (tencentFailure != null || eastMoneyFailure != null) {
            logger.warn("错杀估值池实时行情全部不可用，降级为数据复核状态", eastMoneyFailure == null ? tencentFailure : eastMoneyFailure);
        }
        return new QuoteFetchResult(List.of(), DEGRADED_QUOTE_NOTE);
    }

    private List<String> missingOrInvalidPriceSymbols(List<String> symbols, List<EastMoneyQuote> quotes) {
        Map<String, EastMoneyQuote> fetchedQuotes = quotes.stream()
                .collect(Collectors.toMap(EastMoneyQuote::symbol, Function.identity(), (left, right) -> left));
        return symbols.stream()
                .filter(symbol -> !hasUsablePerSharePrice(fetchedQuotes.get(symbol)))
                .toList();
    }

    private List<EastMoneyQuote> mergeQuotes(List<EastMoneyQuote> primaryQuotes, List<EastMoneyQuote> fallbackQuotes) {
        Map<String, EastMoneyQuote> merged = new LinkedHashMap<>();
        primaryQuotes.forEach(quote -> merged.put(quote.symbol(), quote));
        fallbackQuotes.forEach(quote -> merged.merge(
                quote.symbol(),
                quote,
                (primary, fallback) -> hasUsablePerSharePrice(primary) || !hasUsablePerSharePrice(fallback) ? primary : fallback
        ));
        return new ArrayList<>(merged.values());
    }

    private boolean hasUsablePerSharePrice(EastMoneyQuote quote) {
        if (quote == null || quote.latestPrice() == null) {
            return false;
        }
        return quote.latestPrice().compareTo(BigDecimal.ZERO) > 0
                && quote.latestPrice().compareTo(new BigDecimal("100000")) < 0;
    }

    private EastMoneyQuote mergeQuote(EastMoneyQuote preferred, EastMoneyQuote fallback) {
        if (preferred == null) {
            return fallback;
        }
        if (fallback == null) {
            return preferred;
        }
        EastMoneyQuote priceSource = hasUsablePerSharePrice(preferred)
                ? preferred
                : hasUsablePerSharePrice(fallback) ? fallback : null;
        return new EastMoneyQuote(
                firstText(preferred.symbol(), fallback.symbol()),
                firstText(preferred.name(), fallback.name()),
                firstText(preferred.market(), fallback.market()),
                firstText(preferred.industry(), fallback.industry()),
                priceSource == null ? null : priceSource.latestPrice(),
                firstPresent(preferred.changePercent(), fallback.changePercent()),
                firstPresent(preferred.turnoverRate(), fallback.turnoverRate()),
                firstPresent(preferred.volume(), fallback.volume()),
                firstPresent(preferred.amount(), fallback.amount()),
                firstPresent(preferred.peRatio(), fallback.peRatio()),
                firstPresent(preferred.pbRatio(), fallback.pbRatio()),
                firstPresent(preferred.peTtm(), fallback.peTtm()),
                priceSource == null ? "全市场行情价格不可用" : priceSource.sourceName(),
                priceSource == null ? firstText(preferred.quoteUrl(), fallback.quoteUrl()) : priceSource.quoteUrl(),
                priceSource == null ? null : priceSource.fetchedAt(),
                priceSource == null ? null : priceSource.tradeDate(),
                priceSource == null ? null : priceSource.marketTimestamp()
        );
    }

    private boolean passesDynamicPreFilter(EastMoneyQuote quote, MispricingRuleSet ruleSet) {
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        if (!hasUsablePerSharePrice(quote) || pe == null || pb == null) {
            return false;
        }
        if (pe.compareTo(BigDecimal.ZERO) <= 0 || pb.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal loosePeCeiling = ruleSet.maxPeForValue().multiply(new BigDecimal("1.80")).max(new BigDecimal("32"));
        BigDecimal loosePbCeiling = ruleSet.maxPbForValue().multiply(new BigDecimal("1.80")).max(new BigDecimal("4.50"));
        if (pe.compareTo(loosePeCeiling) > 0 || pb.compareTo(loosePbCeiling) > 0) {
            return false;
        }
        if (!RecommendationQuality.hasSufficientLiquidity(quote)) {
            return false;
        }
        String name = quote.name() == null ? "" : quote.name().toUpperCase();
        return !name.contains("ST");
    }

    private AssetSeed dynamicSeed(EastMoneyQuote quote, MispricingRuleSet ruleSet) {
        String assetGroup = dynamicAssetGroup(marketDescriptor(quote));
        int quality = dynamicQualityScore(quote, ruleSet);
        int cashflow = dynamicCashflowScore(quote);
        return seed(
                quote.symbol(),
                quote.name(),
                assetGroup,
                quality,
                cashflow,
                dynamicStrengths(quote, assetGroup),
                dynamicRisks(quote)
        );
    }

    private String dynamicAssetGroup(String industry) {
        if (containsAny(industry, "火电", "农业", "养殖", "有色", "钢铁", "化工", "水泥", "建材", "航运", "电池")) {
            return "周期修复观察";
        }
        if (containsAny(industry, "银行")) {
            return "低估金融";
        }
        if (containsAny(industry, "保险")) {
            return "保险修复";
        }
        if (containsAny(industry, "电力", "公用", "高速", "铁路", "港口", "机场")) {
            return "现金流防守";
        }
        if (containsAny(industry, "食品", "饮料", "乳品", "家电", "医药", "中药")) {
            return "低估消费医药";
        }
        if (containsAny(industry, "煤炭", "石油", "能源")) {
            return "能源红利";
        }
        if (containsAny(industry, "半导体", "电子", "通信", "软件", "计算机", "机器人", "自动化")) {
            return "科技回撤候选";
        }
        return "全市场低估观察";
    }

    private int dynamicQualityScore(EastMoneyQuote quote, MispricingRuleSet ruleSet) {
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        int score = 54;
        String descriptor = marketDescriptor(quote);
        if (containsAny(descriptor, "银行", "家电", "美的", "格力", "海尔")) {
            score += 20;
        } else if (containsAny(descriptor, "电力", "公用", "高速", "食品", "饮料", "乳品", "伊利", "双汇")) {
            score += 18;
        } else if (containsAny(descriptor, "保险", "平安", "太保", "医药", "中药", "煤炭", "石油")) {
            score += 14;
        } else if (containsAny(descriptor, "半导体", "电子", "通信", "软件", "计算机")) {
            score += 9;
        } else if (containsAny(descriptor, "农业", "养殖", "有色", "钢铁", "化工", "电池")) {
            score += 5;
        }
        if (pe != null && pe.compareTo(BigDecimal.ZERO) > 0 && pe.compareTo(ruleSet.maxPeForValue()) <= 0) {
            score += 5;
        }
        if (pb != null && pb.compareTo(ruleSet.maxPbForValue()) <= 0) {
            score += 5;
        }
        if (quote.amount() != null && quote.amount().compareTo(new BigDecimal("100000000")) >= 0) {
            score += 4;
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("5")) > 0) {
            score -= 8;
        }
        if (quote.industry() == null || quote.industry().isBlank()) {
            score -= 8;
        }
        return Math.max(40, Math.min(92, score));
    }

    private int dynamicCashflowScore(EastMoneyQuote quote) {
        int score = 58;
        String descriptor = marketDescriptor(quote);
        if (containsAny(descriptor, "银行", "保险", "平安", "太保", "电力", "公用", "高速", "铁路", "煤炭", "石油")) {
            score += 22;
        } else if (containsAny(descriptor, "食品", "饮料", "乳品", "家电", "医药", "中药", "伊利", "双汇", "美的", "格力", "海尔")) {
            score += 16;
        } else if (containsAny(descriptor, "半导体", "电子", "通信", "软件", "计算机")) {
            score += 8;
        } else if (containsAny(descriptor, "农业", "养殖", "有色", "钢铁", "化工")) {
            score += 4;
        }
        if (quote.pbRatio() != null && quote.pbRatio().compareTo(new BigDecimal("1.20")) <= 0) {
            score += 4;
        }
        return Math.max(42, Math.min(90, score));
    }

    private List<String> dynamicStrengths(EastMoneyQuote quote, String assetGroup) {
        List<String> strengths = new ArrayList<>();
        strengths.add("来自全 A 股动态初筛，不是固定股票白名单");
        strengths.add(assetGroup + "：行业属性符合错杀修复或防守轮动观察");
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        if (pe != null) {
            strengths.add("PE TTM " + display(pe) + "，已进入全市场估值漏斗");
        }
        if (quote.pbRatio() != null) {
            strengths.add("PB " + display(quote.pbRatio()) + "，用于判断资产折价程度");
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(BigDecimal.ZERO) <= 0) {
            strengths.add("当日没有追涨，价格行为更接近错杀观察窗口");
        }
        return strengths;
    }

    private List<String> dynamicRisks(EastMoneyQuote quote) {
        List<String> risks = new ArrayList<>();
        risks.add("动态候选质量分为行业、估值和流动性代理分，尚未替代十年财报和公告 Agent 结论");
        if (quote.industry() == null || quote.industry().isBlank()) {
            risks.add("行业字段缺失，业务属性需要人工或公告复核");
        }
        if (!RecommendationQuality.hasSufficientLiquidity(quote)) {
            risks.add(RecommendationQuality.liquidityRiskText());
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("3")) > 0) {
            risks.add("当日涨幅偏大，不能把情绪拉升误判为错杀买点");
        }
        if (dynamicAssetGroup(marketDescriptor(quote)).contains("周期")) {
            risks.add("周期修复观察必须补产品价格、库存、毛利率和现金流证据");
        }
        return risks;
    }

    private String marketDescriptor(EastMoneyQuote quote) {
        if (quote == null) {
            return "";
        }
        return (quote.industry() == null ? "" : quote.industry())
                + " "
                + (quote.name() == null ? "" : quote.name());
    }

    private ScoredAsset score(AssetSeed seed, EastMoneyQuote quote, StyleHeatSnapshot heat, MispricingRuleSet ruleSet) {
        BigDecimal quality = BigDecimal.valueOf(seed.qualityScore());
        BigDecimal valuation = valuationScore(quote, ruleSet);
        BigDecimal cashflow = BigDecimal.valueOf(seed.cashflowDefenseScore());
        BigDecimal timing = timingScore(quote, heat);
        BigDecimal finalScore = weighted(heat.heatScore(), "0.20")
                .add(weighted(quality, "0.25"))
                .add(weighted(valuation, "0.30"))
                .add(weighted(cashflow, "0.15"))
                .add(weighted(timing, "0.10"));
        if (quote == null || quote.latestPrice() == null || firstPresent(quote.peTtm(), quote.peRatio()) == null || quote.pbRatio() == null) {
            finalScore = finalScore.min(new BigDecimal("42"));
        }
        MispricingScoreBreakdown breakdown = new MispricingScoreBreakdown(
                scale(heat.heatScore()),
                scale(quality),
                scale(valuation),
                scale(cashflow),
                scale(timing),
                scale(clamp(finalScore))
        );
        ActionDecision decision = decide(seed, quote, heat, breakdown, ruleSet);
        RecommendationEvidenceBundle evidenceBundle = evidenceEnrichmentService.enrichForList(seed.symbol());
        EvidenceCompleteness completeness = mispricingEvidenceCompleteness(seed, quote, evidenceBundle);
        MispricedAsset asset = new MispricedAsset(
                0,
                seed.symbol(),
                quote == null || quote.name() == null ? seed.name() : quote.name(),
                seed.assetGroup(),
                quote == null ? null : quote.industry(),
                quote == null ? null : quote.latestPrice(),
                quote == null ? null : quote.marketTimestamp(),
                quote == null ? null : quote.changePercent(),
                quote == null ? null : firstPresent(quote.peTtm(), quote.peRatio()),
                quote == null ? null : quote.pbRatio(),
                quote == null ? null : quote.amount(),
                breakdown,
                decision.action(),
                decision.label(),
                decision.reason(),
                evidenceCompletenessService.gateAdvice(todayAdvice(seed, quote, heat, breakdown, decision, ruleSet), completeness),
                seed.strengths(),
                risks(seed, quote, ruleSet),
                entryRules(decision.action(), heat, ruleSet),
                exitRules(ruleSet),
                evidence(seed, heat),
                completeness,
                evidenceBundle,
                reviewResult(seed, quote, heat, breakdown, decision, ruleSet)
        );
        return new ScoredAsset(asset);
    }

    private EvidenceCompleteness mispricingEvidenceCompleteness(AssetSeed seed, EastMoneyQuote quote, RecommendationEvidenceBundle evidenceBundle) {
        boolean hasQuote = quote != null && quote.latestPrice() != null && quote.amount() != null;
        boolean hasValuation = quote != null && firstPresent(quote.peTtm(), quote.peRatio()) != null && quote.pbRatio() != null;
        boolean hasFilingReview = evidenceBundle.hasExecutableConsensus();
        return evidenceCompletenessService.evaluate(EvidenceCompletenessInput.longTerm(
                hasQuote,
                hasValuation,
                false,
                false,
                hasFilingReview,
                evidenceBundle.hasIndustryComparison(),
                false,
                mergeGaps(evidenceBundle.dataGaps(), List.of(FINANCIAL_HISTORY_GAP))
        ));
    }

    private List<String> mergeGaps(List<String> primary, List<String> secondary) {
        List<String> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return merged.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
    }

    private ActionDecision decide(AssetSeed seed, EastMoneyQuote quote, StyleHeatSnapshot heat, MispricingScoreBreakdown score, MispricingRuleSet ruleSet) {
        BigDecimal pe = quote == null ? null : firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote == null ? null : quote.pbRatio();
        if (quote == null || pe == null || pb == null) {
            return new ActionDecision("DATA_REVIEW", "数据不足", "行情或估值字段缺失，本轮不纳入错杀池。");
        }
        if (seed.qualityScore() < ruleSet.minQualityScore().intValue() && isCyclicalReview(seed)) {
            return new ActionDecision("CYCLICAL_OBSERVE", "周期交易观察", "估值可能有弹性，但逻辑来自周期变量，不属于优质资产错杀。");
        }
        if (seed.qualityScore() < ruleSet.minQualityScore().intValue()) {
            return new ActionDecision("VALUE_TRAP_EXCLUDED", "剔除优质错杀", "估值可能便宜，但质量分不足，不能当作优质资产错杀。");
        }
        if (heat.heatScore().compareTo(ruleSet.hotOverheatThreshold()) < 0) {
            return new ActionDecision("WAIT_HOT_OVERHEAT", "等热门过热", "热门方向尚未达到过热阈值，错杀埋伏的赔率还不够。");
        }
        boolean valueOk = pe.compareTo(BigDecimal.ZERO) > 0
                && pe.compareTo(ruleSet.maxPeForValue()) <= 0
                && pb.compareTo(ruleSet.maxPbForValue()) <= 0;
        if (!valueOk) {
            return new ActionDecision("WATCH_PULLBACK", "估值未深折", "质量可以观察，但估值没有进入错杀池阈值，不能因为热门过热就买。");
        }
        if (isChasingRisk(quote)) {
            return new ActionDecision("WAIT_WEAK_DAY", "等弱势日", "标的当日已经明显上涨，不符合“被错杀”买点，等待回落或相对强度重新确认。");
        }
        if (valueOk && score.finalScore().compareTo(new BigDecimal("78")) >= 0) {
            return new ActionDecision("EVIDENCE_REVIEW", "财报复核", "行情级质量代理、估值和弱势日条件匹配，但真实财务历史尚未接入，只能进入证据复核池。");
        }
        if (score.finalScore().compareTo(new BigDecimal("70")) >= 0) {
            return new ActionDecision("WATCH_PULLBACK", "错杀观察", "方向符合风格切换逻辑，但估值或触发条件还需更好的买点。");
        }
        return new ActionDecision("WAIT_CONFIRM", "等待确认", "当前错杀证据不足，先等待相对强度或基本面信号。");
    }

    private StyleHeatSnapshot resolveStyleHeat(BigDecimal override) {
        if (override != null && override.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal heat = clamp(override);
            return new StyleHeatSnapshot(
                    "手动热门方向",
                    scale(heat),
                    scale(heat),
                    scale(heat),
                    riskLabel(heat),
                    List.of("使用页面手动输入的热门过热分数", "过热分数越高，非热门优质资产错杀权重越高")
            );
        }
        TechTrackingReport techReport;
        try {
            techReport = techTrackingService.report(21, null, null, null, null);
        } catch (RuntimeException ex) {
            return autoHeatFallback(ex);
        }
        List<TechTrackedStock> hot = techReport.candidates();
        if (hot.isEmpty()) {
            return new StyleHeatSnapshot("科技主线", new BigDecimal("50"), new BigDecimal("50"), new BigDecimal("50"), "中性", List.of("科技追踪池暂无可用候选"));
        }
        BigDecimal valuationPressure = average(hot.stream().map(this::techValuationPressure).toList());
        BigDecimal crowdingPressure = average(hot.stream().map(item -> BigDecimal.valueOf(techActionPressure(item.action()))).toList());
        BigDecimal heat = clamp(weighted(valuationPressure, "0.65").add(weighted(crowdingPressure, "0.35")));
        return new StyleHeatSnapshot(
                "科技主线",
                scale(heat),
                scale(valuationPressure),
                scale(crowdingPressure),
                riskLabel(heat),
                List.of(
                        "科技追踪池候选数 " + hot.size(),
                        "估值压力 " + scale(valuationPressure),
                        "交易拥挤压力 " + scale(crowdingPressure),
                        "热门过热越高，越适合寻找被虹吸错杀的稳定现金流资产"
                )
        );
    }

    private StyleHeatSnapshot autoHeatFallback(RuntimeException ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "未知错误" : ex.getMessage();
        return new StyleHeatSnapshot(
                "科技主线",
                new BigDecimal("60"),
                new BigDecimal("60"),
                new BigDecimal("60"),
                "中性",
                List.of(
                        "科技追踪池自动热度暂不可用，已按中性热度降级：" + message,
                        "本次没有足够证据判断热门方向过热，请手动输入热门过热分后重跑"
                )
        );
    }

    private BigDecimal techValuationPressure(TechTrackedStock stock) {
        BigDecimal pe = stock.peTtm();
        BigDecimal pb = stock.pbRatio();
        BigDecimal pePressure = ratioPressure(pe, new BigDecimal("40"), new BigDecimal("160"));
        BigDecimal pbPressure = ratioPressure(pb, new BigDecimal("6"), new BigDecimal("35"));
        return clamp(weighted(pePressure, "0.55").add(weighted(pbPressure, "0.45")));
    }

    private int techActionPressure(String action) {
        return switch (action) {
            case "THEME_ONLY" -> 95;
            case "SMALL_TREND" -> 78;
            case "WATCH_CONFIRM" -> 62;
            case "WAIT_PULLBACK" -> 55;
            case "WATCH_DATA" -> 45;
            default -> 35;
        };
    }

    private BigDecimal ratioPressure(BigDecimal value, BigDecimal low, BigDecimal high) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("50");
        }
        if (value.compareTo(low) <= 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(high) >= 0) {
            return new BigDecimal("100");
        }
        return value.subtract(low).multiply(new BigDecimal("100")).divide(high.subtract(low), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal valuationScore(EastMoneyQuote quote, MispricingRuleSet ruleSet) {
        if (quote == null) {
            return new BigDecimal("35");
        }
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        BigDecimal peScore = scorePe(pe, ruleSet);
        BigDecimal pbScore = scorePb(pb, ruleSet);
        return clamp(weighted(peScore, "0.60").add(weighted(pbScore, "0.40")));
    }

    private BigDecimal scorePe(BigDecimal pe, MispricingRuleSet ruleSet) {
        if (pe == null) {
            return new BigDecimal("40");
        }
        if (pe.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("10");
        }
        if (pe.compareTo(new BigDecimal("8")) <= 0) {
            return new BigDecimal("98");
        }
        if (pe.compareTo(new BigDecimal("12")) <= 0) {
            return new BigDecimal("90");
        }
        if (pe.compareTo(ruleSet.maxPeForValue()) <= 0) {
            return new BigDecimal("78");
        }
        if (pe.compareTo(new BigDecimal("25")) <= 0) {
            return new BigDecimal("58");
        }
        return new BigDecimal("35");
    }

    private BigDecimal scorePb(BigDecimal pb, MispricingRuleSet ruleSet) {
        if (pb == null) {
            return new BigDecimal("40");
        }
        if (pb.compareTo(new BigDecimal("0.80")) <= 0) {
            return new BigDecimal("98");
        }
        if (pb.compareTo(new BigDecimal("1.50")) <= 0) {
            return new BigDecimal("90");
        }
        if (pb.compareTo(ruleSet.maxPbForValue()) <= 0) {
            return new BigDecimal("76");
        }
        if (pb.compareTo(new BigDecimal("4")) <= 0) {
            return new BigDecimal("55");
        }
        return new BigDecimal("28");
    }

    private BigDecimal timingScore(EastMoneyQuote quote, StyleHeatSnapshot heat) {
        BigDecimal score = heat.heatScore().compareTo(new BigDecimal("75")) >= 0 ? new BigDecimal("82") : new BigDecimal("62");
        if (quote == null || quote.changePercent() == null) {
            return score.subtract(new BigDecimal("8"));
        }
        BigDecimal change = quote.changePercent();
        if (change.compareTo(new BigDecimal("-1")) <= 0) {
            return score.add(new BigDecimal("10"));
        }
        if (change.compareTo(new BigDecimal("0.5")) <= 0) {
            return score.add(new BigDecimal("6"));
        }
        if (change.compareTo(new BigDecimal("3")) > 0) {
            return score.subtract(new BigDecimal("28"));
        }
        if (change.compareTo(MAX_ENTRY_RISE_PERCENT) > 0) {
            return score.subtract(new BigDecimal("18"));
        }
        return score;
    }

    private List<String> risks(AssetSeed seed, EastMoneyQuote quote, MispricingRuleSet ruleSet) {
        List<String> risks = new ArrayList<>(seed.risks());
        if (quote != null) {
            BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
            BigDecimal pb = quote.pbRatio();
            if (pe != null && pe.compareTo(ruleSet.maxPeForValue()) > 0) {
                risks.add("PE 高于错杀池价值阈值 " + ruleSet.maxPeForValue() + "，需要等待更好价格或更强业绩");
            }
            if (pb != null && pb.compareTo(ruleSet.maxPbForValue()) > 0) {
                risks.add("PB 高于错杀池价值阈值 " + ruleSet.maxPbForValue() + "，并非深度折价");
            }
        }
        return risks.stream().distinct().toList();
    }

    private List<String> entryRules(String action, StyleHeatSnapshot heat, MispricingRuleSet ruleSet) {
        if ("CYCLICAL_OBSERVE".equals(action)) {
            return List.of(
                    "先确认周期变量：产品价格、煤价、电价、库存或需求没有继续恶化",
                    "只在周期底部右侧信号出现后小仓位观察，不按优质资产错杀重仓",
                    "若热门方向过热但本资产没有相对强度，继续等待",
                    "估值便宜只能作为辅助条件，不能替代基本面拐点"
            );
        }
        if ("EVIDENCE_REVIEW".equals(action)) {
            return List.of(
                    "补齐至少三年年度 ROE、毛利率、经营现金流和利润质量",
                    "核验最新公告不存在业绩、负债或治理反证",
                    "财务证据完成前只保留观察，不执行新增仓位"
            );
        }
        return List.of(
                "等待热门方向进一步过热或出现震荡",
                "等待标的放量站回 20 日线或相对强度转正",
                "估值回落到 PE/PB 阈值以内再升级"
        );
    }

    private List<String> exitRules(MispricingRuleSet ruleSet) {
        return List.of(
                "买入后继续弱于大盘并回撤超过 " + ruleSet.stopLossPercent() + "%，先退出",
                "基本面证据恶化，不再按错杀处理",
                "热门方向降温后标的仍没有相对收益，降低优先级",
                "估值修复到历史中位或股息/现金流赔率下降，分批止盈"
        );
    }

    private TradingAdvice todayAdvice(
            AssetSeed seed,
            EastMoneyQuote quote,
            StyleHeatSnapshot heat,
            MispricingScoreBreakdown score,
            ActionDecision decision,
            MispricingRuleSet ruleSet
    ) {
        if (quote == null) {
            return new TradingAdvice(
                    "WAIT",
                    "观察",
                    30,
                    "今日行情和估值字段缺失，先不输出买卖动作。",
                    List.of("缺少最新价、涨跌幅或估值数据"),
                    List.of("补齐行情源后重新计算", "数据缺失时不做加仓或卖出判断")
            );
        }
        if ("VALUE_TRAP_EXCLUDED".equals(decision.action())) {
            return new TradingAdvice(
                    "SELL_ALL",
                    "全仓卖出",
                    70,
                    "质量分未达优质错杀阈值，若已有仓位不再按错杀逻辑持有。",
                    List.of("质量分 " + seed.qualityScore() + " 低于阈值 " + ruleSet.minQualityScore(), "系统结论为剔除优质错杀"),
                    List.of("若仍想交易，只能另建周期或事件规则", "不要因为低 PE/PB 自动补仓")
            );
        }
        if ("CYCLICAL_OBSERVE".equals(decision.action())) {
            return new TradingAdvice(
                    "HOLD",
                    "持有",
                    58,
                    "今日只适合持有观察，不按优质资产错杀加仓。",
                    List.of("本标的更偏周期交易", priceActionFinding(quote)),
                    List.of("等产品价格、毛利率或现金流出现右侧确认后再加仓", "跌破周期底部假设时退出观察")
            );
        }
        if ("EVIDENCE_REVIEW".equals(decision.action())) {
            return new TradingAdvice(
                    "WAIT",
                    "财报待补",
                    55,
                    "行情和估值已进入错杀研究区，但近三年点时财报尚未核验，今日不新增仓位。",
                    List.of(
                            "热门过热分 " + heat.heatScore() + " 达标",
                            "当前质量分 " + score.qualityScore() + " 仅为人工或行业代理，不是真实财报结论",
                            valuationFinding(quote, ruleSet),
                            FINANCIAL_HISTORY_GAP
                    ),
                    List.of("补齐年度财务序列", "完成公告反证和 Agent 复核后重新计算")
            );
        }
        if ("WAIT_WEAK_DAY".equals(decision.action())) {
            return new TradingAdvice(
                    "HOLD",
                    "持有",
                    66,
                    "质量和估值可以观察，但今日价格没有给出加仓点。",
                    List.of(valuationFinding(quote, ruleSet), priceActionFinding(quote)),
                    List.of("等待回撤或缩量企稳后再评估", "当日明显上涨时不追")
            );
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("5")) >= 0) {
            return new TradingAdvice(
                    "BATCH_SELL",
                    "分批卖出",
                    68,
                    "今日涨幅较大，若前期已有仓位，优先锁定部分利润。",
                    List.of(priceActionFinding(quote), "本模块目标是低位错杀修复，不追单日急涨"),
                    List.of("至少保留一部分观察仓等待趋势确认", "若后续基本面证据增强，可重新计算后恢复持有")
            );
        }
        return new TradingAdvice(
                "HOLD",
                "持有",
                60,
                "今日不满足新增仓位条件，已有仓位以持有观察为主。",
                List.of(decision.reason(), valuationFinding(quote, ruleSet)),
                List.of("等待估值、价格行为或热门过热条件继续改善", "若基本面证据恶化则降低仓位")
        );
    }

    private List<MispricingEvidenceItem> evidence(AssetSeed seed, StyleHeatSnapshot heat) {
        List<MispricingEvidenceItem> evidence = new ArrayList<>(List.of(
                new MispricingEvidenceItem(
                        seed.assetGroup() + "质量代理线索",
                        "以下内容来自人工种子或行业代理，不能替代财报：" + String.join("；", seed.strengths()),
                        null,
                        50
                ),
                new MispricingEvidenceItem(
                        "热门拥挤对照",
                        "当前热门方向过热分为 " + heat.heatScore() + "，过热阶段更容易出现非热门优质资产的资金虹吸错杀。",
                        null,
                        88
                )
        ));
        if (isCyclicalReview(seed)) {
            evidence.add(new MispricingEvidenceItem(
                    "周期属性复核",
                    seed.name() + " 被识别为 " + seed.assetGroup()
                            + "，必须跟踪行业供需、产品价格、库存/产能及其向毛利率和现金流的传导，不能仅凭低 PE/PB 认定为优质资产错杀。",
                    null,
                    60
            ));
        }
        return evidence;
    }

    private MispricingReviewResult reviewResult(
            AssetSeed seed,
            EastMoneyQuote quote,
            StyleHeatSnapshot heat,
            MispricingScoreBreakdown score,
            ActionDecision decision,
            MispricingRuleSet ruleSet
    ) {
        if (isCyclicalReview(seed)) {
            List<MispricingEvidenceItem> sources = quote == null ? List.of() : List.of(new MispricingEvidenceItem(
                    quoteSourceTitle(quote),
                    "用于核验最新价、涨跌幅、PE、PB 和成交额；周期供需证据仍待独立补充。",
                    quote.quoteUrl(),
                    70
            ));
            return new MispricingReviewResult(
                    "CYCLICAL_ONLY",
                    "系统已核验：只按周期交易观察",
                    seed.name() + " 的主要赔率来自周期变量，本轮剔出优质错杀池，仅保留周期交易观察。",
                    List.of(
                            "资产组为 " + seed.assetGroup() + "，盈利更容易受行业价格、供需、库存或成本变化影响。",
                            "估值便宜只能解释赔率，不能替代周期拐点和财务传导验证。",
                            "质量分 " + seed.qualityScore() + " 低于优质错杀阈值 " + ruleSet.minQualityScore() + "。"
                    ),
                    List.of("行业供需和产品价格尚未形成可核验拐点", "毛利率与经营现金流的同步改善尚未验证"),
                    sources
            );
        }
        if ("EVIDENCE_REVIEW".equals(decision.action())) {
            return new MispricingReviewResult(
                    "EVIDENCE_REQUIRED",
                    "系统已核验：财报证据待补",
                    "热门过热只是背景，当前仅通过行情、估值和价格行为检查；质量仍是代理分，尚不能认定为优质资产错杀。",
                    List.of(
                            "热门过热分 " + heat.heatScore() + " 达到阈值 " + ruleSet.hotOverheatThreshold() + "。",
                            "代理质量分 " + score.qualityScore() + " 达到研究阈值，但不作为财报证据。",
                            valuationFinding(quote, ruleSet),
                            priceActionFinding(quote)
                    ),
                    List.of("近三年点时财报缺失", "公告反证完成前不得执行买入"),
                    List.of(new MispricingEvidenceItem(
                            quoteSourceTitle(quote),
                            "用于核验最新价、PE、PB 和成交额。",
                            quote == null ? null : quote.quoteUrl(),
                            86
                    ))
            );
        }
        if ("WAIT_WEAK_DAY".equals(decision.action())) {
            return new MispricingReviewResult(
                    "WAIT_PRICE_CONFIRM",
                    "系统已核验：价格未给买点",
                    "资产质量和估值可以继续观察，但当日价格不弱；当前不是“被错杀”的执行点，需要等回落或相对强度确认。",
                    List.of(
                            valuationFinding(quote, ruleSet),
                            priceActionFinding(quote)
                    ),
                    List.of("当日涨幅超过 " + MAX_ENTRY_RISE_PERCENT + "%，不满足弱势日纪律"),
                    List.of(new MispricingEvidenceItem(
                            quoteSourceTitle(quote),
                            "用于核验价格行为、PE、PB 和成交额。",
                            quote == null ? null : quote.quoteUrl(),
                            82
                    ))
            );
        }
        if ("WAIT_HOT_OVERHEAT".equals(decision.action())) {
            return new MispricingReviewResult(
                    "WAIT_TRIGGER",
                    "系统已核验：条件未触发",
                    "资产质量或估值可观察，但热门方向尚未达到过热阈值，不构成“被热门方向错杀”的买点。",
                    List.of(valuationFinding(quote, ruleSet)),
                    List.of("热门过热分 " + heat.heatScore() + " 低于阈值 " + ruleSet.hotOverheatThreshold()),
                    List.of(new MispricingEvidenceItem(
                            quoteSourceTitle(quote),
                            "用于核验估值条件。",
                            quote == null ? null : quote.quoteUrl(),
                            80
                    ))
            );
        }
        return new MispricingReviewResult(
                "NOT_PASSED",
                "系统已核验：未进入错杀池",
                decision.reason(),
                quote == null ? List.of() : List.of(valuationFinding(quote, ruleSet)),
                List.of(decision.reason()),
                quote == null ? List.of() : List.of(new MispricingEvidenceItem(
                        quoteSourceTitle(quote),
                        "用于核验估值条件。",
                        quote.quoteUrl(),
                        78
                ))
        );
    }

    private String valuationFinding(EastMoneyQuote quote, MispricingRuleSet ruleSet) {
        if (quote == null) {
            return "行情源缺失，无法核验 PE/PB。";
        }
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        return "PE " + display(pe) + " / PB " + display(pb)
                + "，阈值为 PE<=" + ruleSet.maxPeForValue() + "、PB<=" + ruleSet.maxPbForValue() + "。";
    }

    private String priceActionFinding(EastMoneyQuote quote) {
        if (quote == null || quote.changePercent() == null) {
            return "价格行为缺失，无法确认是否弱势日。";
        }
        return "当日涨跌幅 " + display(quote.changePercent()) + "%，弱势日纪律要求不追涨，超过 "
                + MAX_ENTRY_RISE_PERCENT + "% 则等待。";
    }

    private List<MispricingEvidenceItem> policySignals() {
        return List.of(
                new MispricingEvidenceItem("红利与长期资金配置", "稳定现金流、高分红和低估值资产在市场高波动阶段具备承接价值。", null, 86),
                new MispricingEvidenceItem("内需与消费修复", "家电、食品饮料、医药等行业若基本面未恶化，可能在科技过热后获得估值回归。", null, 78),
                new MispricingEvidenceItem("公用事业和能源安全", "电力、能源、高速等资产现金流稳定，和科技主题相关性较低。", null, 82)
        );
    }

    private MispricingRuleSet resolveRuleSet(BigDecimal maxPe, BigDecimal maxPb, BigDecimal minQuality, Integer scanLimit) {
        return new MispricingRuleSet(
                DEFAULT_RULE_SET.hotOverheatThreshold(),
                positive(maxPe, DEFAULT_RULE_SET.maxPeForValue()),
                positive(maxPb, DEFAULT_RULE_SET.maxPbForValue()),
                positive(minQuality, DEFAULT_RULE_SET.minQualityScore()),
                DEFAULT_RULE_SET.preferredPullbackPercent(),
                DEFAULT_RULE_SET.stopLossPercent(),
                Math.max(50, Math.min(scanLimit == null ? DEFAULT_RULE_SET.scanLimit() : scanLimit, MAX_SCAN_LIMIT))
        );
    }

    private String riskLabel(BigDecimal heat) {
        if (heat.compareTo(new BigDecimal("80")) >= 0) {
            return "高过热";
        }
        if (heat.compareTo(new BigDecimal("65")) >= 0) {
            return "偏热";
        }
        if (heat.compareTo(new BigDecimal("45")) >= 0) {
            return "中性";
        }
        return "不热";
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return fallback;
        }
        return value;
    }

    private BigDecimal weighted(BigDecimal value, String weight) {
        return value.multiply(new BigDecimal(weight));
    }

    private BigDecimal firstPresent(BigDecimal primary, BigDecimal fallback) {
        if (primary != null && primary.compareTo(BigDecimal.ZERO) != 0) {
            return primary;
        }
        return fallback;
    }

    private String firstText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(new BigDecimal("100")) > 0) {
            return new BigDecimal("100");
        }
        return value;
    }

    private MispricedAsset rerank(MispricedAsset asset, int rank) {
        return new MispricedAsset(
                rank,
                asset.symbol(),
                asset.name(),
                asset.assetGroup(),
                asset.industry(),
                asset.latestPrice(),
                asset.marketTimestamp(),
                asset.changePercent(),
                asset.peTtm(),
                asset.pbRatio(),
                asset.amount(),
                asset.score(),
                asset.action(),
                asset.actionLabel(),
                asset.reason(),
                asset.todayAdvice(),
                asset.strengths(),
                asset.risks(),
                asset.entryRules(),
                asset.exitRules(),
                asset.evidence(),
                asset.evidenceCompleteness(),
                asset.evidenceBundle(),
                asset.review()
        );
    }

    private boolean isLongSideways(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        try {
            LocalDate end = LocalDate.now();
            List<EastMoneyKLine> klines = eastMoneyClient.fetchDailyKLines(symbol, end.minusDays(260), end);
            return RecommendationQuality.isLongSideways(klines);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private int actionPriority(String action) {
        return switch (action) {
            case "EVIDENCE_REVIEW" -> 5;
            case "WAIT_WEAK_DAY" -> 4;
            case "WATCH_PULLBACK" -> 4;
            case "WAIT_HOT_OVERHEAT" -> 3;
            case "WAIT_CONFIRM" -> 2;
            case "CYCLICAL_OBSERVE" -> 1;
            case "DATA_REVIEW" -> 1;
            default -> 0;
        };
    }

    private String display(BigDecimal value) {
        return value == null ? "缺失" : scale(value).toPlainString();
    }

    private String quoteSourceTitle(EastMoneyQuote quote) {
        if (quote == null || quote.sourceName() == null || quote.sourceName().isBlank()) {
            return "实时行情与估值";
        }
        return quote.sourceName() + "与估值";
    }

    private boolean isCyclicalReview(AssetSeed seed) {
        return seed.assetGroup().contains("周期");
    }

    private boolean isTradableCommonShare(EastMoneyQuote quote) {
        if (quote == null || quote.symbol() == null || !quote.symbol().matches("\\d{6}")) {
            return false;
        }
        if (!(quote.symbol().startsWith("0")
                || quote.symbol().startsWith("3")
                || quote.symbol().startsWith("4")
                || quote.symbol().startsWith("6")
                || quote.symbol().startsWith("8")
                || quote.symbol().startsWith("92"))) {
            return false;
        }
        String name = quote.name() == null ? "" : quote.name().toUpperCase();
        return !name.contains("ST");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isChasingRisk(EastMoneyQuote quote) {
        return quote != null
                && quote.changePercent() != null
                && quote.changePercent().compareTo(MAX_ENTRY_RISE_PERCENT) > 0;
    }

    private static AssetSeed seed(String symbol, String name, String assetGroup, int qualityScore, int cashflowDefenseScore, List<String> strengths, List<String> risks) {
        return new AssetSeed(symbol, name, assetGroup, qualityScore, cashflowDefenseScore, strengths, risks);
    }

    private record AssetSeed(
            String symbol,
            String name,
            String assetGroup,
            int qualityScore,
            int cashflowDefenseScore,
            List<String> strengths,
            List<String> risks
    ) {
    }

    private record ScoredAsset(MispricedAsset asset) {
    }

    private record QuoteFetchResult(List<EastMoneyQuote> quotes, String quoteNote) {
    }

    private record CandidateUniverse(
            List<AssetSeed> seeds,
            Map<String, EastMoneyQuote> quotes,
            String quoteNote
    ) {
    }

    private record DynamicMergeResult(int scannedCount, int tradableCount, int dynamicCount) {
    }

    private record ActionDecision(String action, String label, String reason) {
    }
}

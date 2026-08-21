package com.aistock.research.shortterm;

import com.aistock.research.factor.RatioScale;
import com.aistock.research.configuration.ShortTermChipSettings;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyIndustryFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.EvidenceCompletenessInput;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.quality.RecommendationQuality;
import com.aistock.research.shortterm.schedule.ShortTermAutomationSettings;
import com.aistock.research.shortterm.chip.ShortTermChipAnalysisService;
import com.aistock.research.shortterm.chip.ShortTermChipSnapshot;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.AsharePriceLimitRule;
import com.aistock.research.trading.QuoteFreshnessService;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import com.aistock.research.trading.TradingClockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.StandardEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class ShortTermService {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermService.class);
    private static final int DEFAULT_LIMIT = 8;
    private static final int DEFAULT_SCAN_LIMIT = 6000;
    private static final int MAX_SCAN_LIMIT = 6000;
    private static final int DEFAULT_KLINE_LIMIT = 120;
    private static final int MAX_KLINE_LIMIT = 160;
    private static final BigDecimal DEFAULT_MIN_AMOUNT = new BigDecimal("80000000");
    private static final BigDecimal DEFAULT_MAX_PRICE_PER_SHARE = new BigDecimal("100");
    private static final BigDecimal DEFAULT_MIN_VOLUME_RATIO = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_MAX_ENTRY_RISE = new BigDecimal("6.50");
    private static final BigDecimal DEFAULT_MAX_DISTANCE_TO_MA20 = new BigDecimal("8.00");
    private static final BigDecimal DEFAULT_MIN_FINANCIAL_SCORE = new BigDecimal("55");
    private static final BigDecimal MIN_RELIABLE_MARKET_COVERAGE = new BigDecimal("0.95");
    private static final BigDecimal MIN_RELIABLE_INDUSTRY_FUND_FLOW_COVERAGE = new BigDecimal("0.70");
    private static final BigDecimal ROE_STRONG = RatioScale.fromPercentPoints("12");
    private static final BigDecimal ROE_ACCEPTABLE = RatioScale.fromPercentPoints("8");
    private static final BigDecimal GROSS_MARGIN_STRONG = RatioScale.fromPercentPoints("30");
    private static final BigDecimal GROSS_MARGIN_ACCEPTABLE = RatioScale.fromPercentPoints("15");
    private static final BigDecimal LARGE_TURNOVER_TAIL_AMOUNT = new BigDecimal("2000000000");
    private static final BigDecimal MEDIUM_TURNOVER_TAIL_AMOUNT = new BigDecimal("800000000");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime ACTIONABLE_TAIL_START = LocalTime.of(14, 45);
    private static final LocalTime ACTIONABLE_TAIL_END_EXCLUSIVE = LocalTime.of(14, 50);
    private static final LocalTime POST_CLOSE_FIXED_PRICE_START = LocalTime.of(15, 5);
    private static final LocalTime POST_CLOSE_FIXED_PRICE_END = LocalTime.of(15, 30);
    private static final String ACTIONABLE_TAIL_LABEL = "14:45-14:49";
    private static final ShortTermWeightProfile WEIGHT_PROFILE = new ShortTermWeightProfile(
            new BigDecimal("0.35"),
            new BigDecimal("0.25"),
            new BigDecimal("0.40"),
            new BigDecimal("0.60"),
            new BigDecimal("0.24"),
            new BigDecimal("0.10"),
            new BigDecimal("0.06")
    );
    private static final List<HotDirectionDefinition> HOT_DIRECTION_DEFINITIONS = List.of(
            new HotDirectionDefinition(
                    "SEMICONDUCTOR_AI",
                    "半导体/国产AI链",
                    List.of("半导体", "芯片", "集成电路", "微电子", "封测", "封装", "光电", "电子", "存储", "晶圆", "算力")
            ),
            new HotDirectionDefinition(
                    "INNOVATIVE_DRUG",
                    "创新药/CXO",
                    List.of("医药", "药业", "制药", "生物", "医疗", "药明", "康德", "创新药", "CXO")
            ),
            new HotDirectionDefinition(
                    "ROBOT_EQUIPMENT",
                    "机器人/高端装备",
                    List.of("机器人", "机械", "机电", "自动化", "智能", "装备", "机床", "重工", "机器", "精工")
            ),
            new HotDirectionDefinition(
                    "NONFERROUS_METALS",
                    "有色/贵金属",
                    List.of("有色", "金属", "铜业", "铝业", "锂业", "钨业", "稀土", "黄金", "白银", "矿业", "钼业", "锡业", "铅锌")
            ),
            new HotDirectionDefinition(
                    "CHEMICAL_MATERIAL",
                    "化工/新材料",
                    List.of("化工", "化学", "材料", "新材", "石化", "纤维", "硅", "酯", "氟", "钛白")
            ),
            new HotDirectionDefinition(
                    "NEW_ENERGY",
                    "新能源/电力设备",
                    List.of("新能源", "光伏", "风电", "电池", "锂电", "储能", "电气", "电源", "电网")
            ),
            new HotDirectionDefinition(
                    "CONSUMER_AGRI",
                    "新消费/农业",
                    List.of("食品", "饮料", "消费", "农业", "养殖", "乳业", "菌业", "种业", "旅游", "零售")
            ),
            new HotDirectionDefinition(
                    "AEROSPACE_LOW_ALTITUDE",
                    "商业航天/低空经济",
                    List.of("航天", "航空", "卫星", "船舶", "军工", "无人机", "通航", "导航", "低空")
            ),
            new HotDirectionDefinition(
                    "COAL_ENERGY",
                    "煤炭/能源",
                    List.of("煤炭", "煤业", "能源", "电力", "石油", "油气")
            )
    );
    private static final String QUOTE_NOTE = "短线右侧模块先做全 A 股行情漏斗，再对候选拉取近一年 K 线、最近年报和同日资金流；影子实验因子不进入生产排序或推荐证据。";

    private final EastMoneyClient eastMoneyClient;
    private final EvidenceCompletenessService evidenceCompletenessService;
    private final TradingClockService tradingClockService;
    private final QuoteFreshnessService quoteFreshnessService;
    private final ShortTermTechnicalSignalEvaluator technicalSignalEvaluator;
    private final ShortTermTradePlanService tradePlanService;
    private final ShortTermAutomationSettings automationSettings;
    private final ShortTermChipAnalysisService chipAnalysisService;
    private final ShortTermChipSettings chipSettings;
    private final ShortTermMomentumQualityEvaluator momentumQualityEvaluator = new ShortTermMomentumQualityEvaluator();
    private final ShortTermSupportReversalEvaluator supportReversalEvaluator = new ShortTermSupportReversalEvaluator();
    private final ShortTermCoreSignalScorer coreSignalScorer = new ShortTermCoreSignalScorer();
    private final ShortTermSupplyDemandScorer supplyDemandScorer = new ShortTermSupplyDemandScorer();
    private final ShortTermCrossSectionAnalyzer crossSectionAnalyzer = new ShortTermCrossSectionAnalyzer();
    private final ShortTermVolatilityQualityEvaluator volatilityQualityEvaluator = new ShortTermVolatilityQualityEvaluator();
    private final ShortTermSignalProfileResolver signalProfileResolver = new ShortTermSignalProfileResolver();
    private final ShortTermMarketRegimeClassifier marketRegimeClassifier = new ShortTermMarketRegimeClassifier();

    public ShortTermService(EastMoneyClient eastMoneyClient) {
        this(eastMoneyClient, new EvidenceCompletenessService(), new TradingClockService(), new ShortTermGoldenCrossAnalyzer());
    }

    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService
    ) {
        this(eastMoneyClient, evidenceCompletenessService, tradingClockService, new ShortTermGoldenCrossAnalyzer());
    }

    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService,
            ShortTermGoldenCrossAnalyzer goldenCrossAnalyzer
    ) {
        this(eastMoneyClient, evidenceCompletenessService, tradingClockService,
                new QuoteFreshnessService(tradingClockService), goldenCrossAnalyzer);
    }

    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService,
            QuoteFreshnessService quoteFreshnessService
    ) {
        this(eastMoneyClient, evidenceCompletenessService, tradingClockService, quoteFreshnessService,
                new ShortTermGoldenCrossAnalyzer());
    }

    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService,
            QuoteFreshnessService quoteFreshnessService,
            ShortTermGoldenCrossAnalyzer goldenCrossAnalyzer
    ) {
        this(
                eastMoneyClient,
                evidenceCompletenessService,
                tradingClockService,
                quoteFreshnessService,
                new ShortTermTechnicalSignalEvaluator(goldenCrossAnalyzer),
                new ShortTermTradePlanService(tradingClockService),
                new ShortTermAutomationSettings(new StandardEnvironment()),
                null,
                new ShortTermChipSettings(new StandardEnvironment())
        );
    }

    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService,
            QuoteFreshnessService quoteFreshnessService,
            ShortTermTechnicalSignalEvaluator technicalSignalEvaluator,
            ShortTermTradePlanService tradePlanService,
            ShortTermAutomationSettings automationSettings
    ) {
        this(
                eastMoneyClient,
                evidenceCompletenessService,
                tradingClockService,
                quoteFreshnessService,
                technicalSignalEvaluator,
                tradePlanService,
                automationSettings,
                null,
                new ShortTermChipSettings(new StandardEnvironment())
        );
    }

    @Autowired
    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService,
            QuoteFreshnessService quoteFreshnessService,
            ShortTermTechnicalSignalEvaluator technicalSignalEvaluator,
            ShortTermTradePlanService tradePlanService,
            ShortTermAutomationSettings automationSettings,
            ShortTermChipAnalysisService chipAnalysisService,
            ShortTermChipSettings chipSettings
    ) {
        this.eastMoneyClient = eastMoneyClient;
        this.evidenceCompletenessService = evidenceCompletenessService;
        this.tradingClockService = tradingClockService;
        this.quoteFreshnessService = quoteFreshnessService;
        this.technicalSignalEvaluator = technicalSignalEvaluator;
        this.tradePlanService = tradePlanService;
        this.automationSettings = automationSettings;
        this.chipAnalysisService = chipAnalysisService;
        this.chipSettings = chipSettings;
    }

    public ShortTermReport report(
            Integer limit,
            Integer scanLimit,
            Integer klineLimit,
            BigDecimal minAmount,
            BigDecimal maxPricePerShare,
            BigDecimal minVolumeRatio,
            BigDecimal maxEntryRise,
            BigDecimal maxDistanceToMa20,
            BigDecimal minFinancialScore
    ) {
        return report(
                limit,
                scanLimit,
                klineLimit,
                minAmount,
                maxPricePerShare,
                minVolumeRatio,
                maxEntryRise,
                maxDistanceToMa20,
                minFinancialScore,
                null
        );
    }

    public ShortTermReport report(
            Integer limit,
            Integer scanLimit,
            Integer klineLimit,
            BigDecimal minAmount,
            BigDecimal maxPricePerShare,
            BigDecimal minVolumeRatio,
            BigDecimal maxEntryRise,
            BigDecimal maxDistanceToMa20,
            BigDecimal minFinancialScore,
            Boolean allowChiNext
    ) {
        return report(new ShortTermScanRequest(
                limit,
                scanLimit,
                klineLimit,
                minAmount,
                maxPricePerShare,
                minVolumeRatio,
                maxEntryRise,
                maxDistanceToMa20,
                minFinancialScore,
                false,
                allowChiNext
        ));
    }

    public ShortTermReport report(ShortTermScanRequest request) {
        return buildReport(request == null ? ShortTermScanRequest.empty() : request);
    }

    private ShortTermReport buildReport(ShortTermScanRequest request) {
        ShortTermRuleSet ruleSet = resolveRuleSet(
                request.scanLimit(),
                request.klineLimit(),
                request.minAmount(),
                request.maxPricePerShare(),
                request.minVolumeRatio(),
                request.maxEntryRise(),
                request.maxDistanceToMa20(),
                request.minFinancialScore(),
                request.allowChiNext()
        );

        AshareQuoteSnapshot quoteSnapshot = fetchMarketQuoteSnapshot(ruleSet.scanLimit());
        LocalDateTime decisionAt = tradingClockService.currentMarketDateTime();
        boolean allowClosedMarketCachePreview = Boolean.TRUE.equals(request.allowStaticCachePreview())
                && tradingClockService.isMarketClosedDay(decisionAt.toLocalDate());
        List<EastMoneyQuote> marketQuotes = uniqueMarketQuotes(quoteSnapshot.quotes());
        List<EastMoneyQuote> pointInTimeCoverageQuotes = marketQuotes.stream()
                .filter(quote -> quoteAvailableAtDecision(quote, decisionAt, allowClosedMarketCachePreview))
                .toList();
        List<EastMoneyQuote> marketContextUniverse = pointInTimeCoverageQuotes.stream()
                .filter(this::isAshareContextQuote)
                .filter(this::hasUsablePrice)
                .toList();
        List<EastMoneyQuote> quoteUniverse = marketContextUniverse.stream()
                .filter(quote -> isTradableCommonShare(quote, ruleSet.allowChiNext()))
                .toList();
        ShortTermCoverageSnapshot coverage = coverageSnapshot(
                quoteSnapshot,
                marketQuotes,
                marketContextUniverse,
                decisionAt,
                false,
                allowClosedMarketCachePreview
        );
        Set<String> unstableIndustrySymbols = fetchUnstableIndustrySymbols();
        List<ShortTermRiskExclusion> quoteExclusions = quoteUniverse.stream()
                .map(quote -> preFilterExclusion(quote, ruleSet, unstableIndustrySymbols))
                .filter(exclusion -> exclusion != null)
                .limit(40)
                .toList();
        List<EastMoneyQuote> preFilteredQuotes = quoteUniverse.stream()
                .filter(quote -> passesQuotePreFilter(quote, ruleSet, unstableIndustrySymbols))
                .toList();
        ShortTermMarketSentiment marketSentiment = marketSentiment(marketContextUniverse, coverage);
        ShortTermMarketRegime marketRegime = marketRegimeClassifier.classify(
                marketContextUniverse,
                coverage,
                marketSentiment
        );
        ShortTermMarketFundDirection marketFundDirection = marketFundDirection(marketContextUniverse);
        List<ShortTermHotDirection> hotDirections = resolveHotDirections(marketContextUniverse);
        if (marketRegime.riskOff() || isExtremeRiskOffMarket(marketSentiment)) {
            return extremeRiskOffReport(
                    ruleSet,
                    quoteUniverse,
                    coverage,
                    marketSentiment,
                    marketRegime,
                    marketFundDirection,
                    quoteExclusions,
                    pointInTimeCoverageQuotes,
                    preFilteredQuotes.size(),
                    marketContextUniverse
            );
        }
        Map<String, ShortTermHotDirection> hotDirectionMap = hotDirections.stream()
                .collect(Collectors.toMap(
                        ShortTermHotDirection::code,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<EastMoneyQuote> reviewPool = preFilteredQuotes;
        List<EastMoneyQuote> tradableQuotes = reviewPool.stream()
                .sorted(Comparator.comparing((EastMoneyQuote quote) -> preliminaryScore(quote, ruleSet, hotDirectionMap)).reversed())
                .limit(ruleSet.klineLimit())
                .toList();
        List<String> reviewedSymbols = tradableQuotes.stream()
                .map(EastMoneyQuote::symbol)
                .toList();

        Map<String, List<EastMoneyKLine>> klineMap = tradableQuotes.isEmpty()
                ? Map.of()
                : fetchKLinesWithSourceGuard(tradableQuotes);

        ShortTermCrossSectionAnalysis crossSection = crossSectionAnalyzer.analyze(
                marketContextUniverse,
                tradableQuotes,
                klineMap
        );

        List<TechnicalCandidate> reviewedTechnicalCandidates = tradableQuotes.stream()
                .map(quote -> technicalCandidate(quote, klineMap.getOrDefault(quote.symbol(), List.of()), ruleSet))
                .toList();

        List<ShortTermRiskExclusion> technicalExclusions = reviewedTechnicalCandidates.stream()
                .map(this::technicalHardExclusion)
                .filter(exclusion -> exclusion != null)
                .toList();
        List<TechnicalCandidate> technicalCandidates = reviewedTechnicalCandidates.stream()
                .filter(candidate -> technicalHardExclusion(candidate) == null)
                .filter(candidate -> !isLongSidewaysWithoutBreakout(candidate, ruleSet))
                .sorted(Comparator.comparingInt(this::goldenCrossTechnicalPriority).reversed()
                        .thenComparing(Comparator.comparing(
                                (TechnicalCandidate item) -> item.coreSignalScore().finalScore()
                        ).reversed()))
                .limit(Math.max(resolveLimit(request.limit()) * 4L, 28L))
                .toList();

        Map<String, ShortTermFinancialSnapshot> financialMap = technicalCandidates.parallelStream()
                .collect(Collectors.toMap(
                        item -> item.quote().symbol(),
                        item -> financialSnapshot(item.quote().symbol()),
                        (left, right) -> left
                ));

        List<ShortTermRiskExclusion> financialExclusions = technicalCandidates.stream()
                .filter(item -> hasSeriousFinancialRedFlag(financialMap.get(item.quote().symbol())))
                .map(item -> riskExclusion(
                        item.quote(),
                        "FINANCIAL_HARD_RISK",
                        "严重财务红旗",
                        "最近财务指标出现严重亏损、经营现金流同步为负或营收与利润明显恶化，不能用于补足短线候选。"
                ))
                .toList();
        List<TechnicalCandidate> eligibleTechnicalCandidates = technicalCandidates.stream()
                .filter(item -> !hasSeriousFinancialRedFlag(financialMap.get(item.quote().symbol())))
                .toList();

        Map<String, EastMoneyFundFlowSnapshot> fundFlowMap = fetchFundFlows(
                eligibleTechnicalCandidates.stream()
                        .map(item -> item.quote().symbol())
                        .toList()
        );

        Map<String, ShortTermChipSnapshot> chipMap = fetchChipSnapshots(
                eligibleTechnicalCandidates,
                klineMap,
                allowExternalChipFetch(decisionAt),
                quoteSnapshot.fetchedAt()
        );

        List<ScoredShortTerm> rawScored = eligibleTechnicalCandidates.stream()
                .map(item -> score(
                        item,
                        financialMap.get(item.quote().symbol()),
                        fundFlowMap.get(item.quote().symbol()),
                        chipMap.get(item.quote().symbol()),
                        crossSection.relativeStrengthBySymbol().get(item.quote().symbol()),
                        crossSection.industryLeadershipBySymbol().get(item.quote().symbol()),
                        ruleSet,
                        hotDirectionMap,
                        marketSentiment,
                        marketRegime
                ))
                .toList();
        List<ScoredShortTerm> scored = attachRankingDiagnostics(rawScored).stream()
                .sorted(shortTermRankingComparator())
                .toList();

        List<ShortTermRiskExclusion> exclusions = java.util.stream.Stream.of(
                        quoteExclusions,
                        technicalExclusions,
                        financialExclusions
                )
                .flatMap(List::stream)
                .limit(80)
                .toList();

        int safeLimit = Math.min(resolveLimit(request.limit()), scored.size());
        List<ShortTermCandidate> candidates = IntStream.range(0, safeLimit)
                .mapToObj(index -> {
                    ShortTermCandidate enriched = enrichTailSignal(
                            rerank(scored.get(index).candidate(), index + 1)
                    );
                    return attachTradePlan(
                            applyCoverageExecutionGate(enriched, coverage),
                            automationSettings.overnightRules()
                    );
                })
                .toList();

        long validKlineCount = reviewedTechnicalCandidates.stream()
                .filter(this::hasSufficientTechnicalReview)
                .count();
        ShortTermTechnicalReviewCoverage technicalReviewCoverage = ShortTermTechnicalReviewCoverage.of(
                reviewPool.size(),
                tradableQuotes.size(),
                (int) validKlineCount
        );
        Instant reportDataCutoffAt = dataCutoffAt(pointInTimeCoverageQuotes, candidates);

        return new ShortTermReport(
                "A 股短线右侧启动池",
                quoteUniverse.size(),
                tradableQuotes.size(),
                (int) validKlineCount,
                candidates.size(),
                quoteNote(coverage, technicalReviewCoverage)
                        + marketFundDirectionNote(marketFundDirection),
                tradingClockService.currentSession(),
                List.of(
                        "第一层使用点时全市场行情计算市场情绪与热门方向，再做流动性、非 ST 和追涨风险过滤；行业成交额地位只软排序，不再硬卡前三。",
                        "第二层拉取候选近一年 K 线，优先确认 MA5/MA10 金叉及其形成阶段，并计算实时换手率和收盘强度。",
                        "第三层技术底分固定为金叉 45%、放量上涨 30%、换手适配 15%、K 线收盘强度 10%。",
                        "波动质量使用 ATR 归一化衡量距 20 日线位置、近期真实波幅收缩与突破扩张，独立贡献限制在 -3 至 +3 分并完整展示。",
                        "市场状态由点时全市场上涨广度、收益中位数、绝对波动、涨跌停近似比例和上涨侧成交额共同划分；退潮不推荐，修复或拥挤高波动只允许轻仓。",
                        "金叉动量、下影承接反转和波动收缩突破保持为独立信号族，页面与历史快照保留主信号族和全部激活证据。",
                        "影子实验因子只保留后台诊断，不进入候选解释、风险理由、证据链、生产排序或动作建议。",
                        "默认输出八个短线候选：可执行层在前、观察层补足展示，但不会为了凑数制造加仓建议。",
                        "普通财务质量只作上下文与风险说明；热门方向、5/10/20 日相对强度与行业成交额地位仅作有界排序修正。",
                        "热门方向可帮助全市场预选，但不能覆盖金叉、放量、换手、上影线和硬风险门禁。",
                        "涨幅过大、距离 20 日线过远、120 日位置过高、量能过度放大会被视为追涨风险，宁可等待回踩确认。",
                        "最终建议只做短线纪律提示：右侧早期也以分批试错为主，跌破关键均线要退出。",
                        "14:45-14:49 使用当时已经产生的分时、均价线、尾盘成交占比和高点回落完成普通股票尾盘决策；14:50 后数据只用于历史复盘，不能反推当日买点。"
                ),
                ruleSet,
                WEIGHT_PROFILE,
                candidates,
                hotDirections,
                marketSentiment,
                marketFundDirection,
                exclusions,
                Map.of(),
                coverage,
                reviewedSymbols,
                reportDataCutoffAt,
                Instant.now(),
                technicalReviewCoverage,
                crossSection.context(),
                marketRegime
        );
    }

    private AshareQuoteSnapshot fetchMarketQuoteSnapshot(int scanLimit) {
        try {
            return eastMoneyClient.fetchAshareQuoteSnapshot(scanLimit);
        } catch (RuntimeException exception) {
            logger.warn("短线右侧全市场行情获取失败", exception);
            throw new IllegalStateException("短线右侧实时行情加载失败，本次不返回空候选降级：" + rootMessage(exception), exception);
        }
    }

    private String quoteNote(
            ShortTermCoverageSnapshot coverage,
            ShortTermTechnicalReviewCoverage technicalCoverage
    ) {
        String effectiveCoverageText = coverage.fetchedCount() + "/" + coverage.expectedCount();
        String rawCoverageText = coverage.rawFetchedCount() + "/" + coverage.rawExpectedCount();
        String acquisitionText = " 行情源原始抓取 " + rawCoverageText
                + (coverage.rawComplete() ? "，原始页完整" : "，原始页不完整")
                + "；无有效现价排除 " + coverage.excludedNoPriceCount() + " 只。";
        ShortTermTechnicalReviewCoverage safeTechnicalCoverage = technicalCoverage == null
                ? ShortTermTechnicalReviewCoverage.unavailable()
                : technicalCoverage;
        String goldenCrossNote = " 技术复核覆盖（金叉K线复核 " + safeTechnicalCoverage.sufficientCount()
                + "/" + safeTechnicalCoverage.quotePreselectedCount() + "）"
                + "，实际请求 " + safeTechnicalCoverage.requestedCount()
                + "；该比例只描述预选池的K线复核进度，绝不替代全市场行情覆盖率。";
        if (coverage.executionReliable()) {
            return QUOTE_NOTE + " 本轮有效行情覆盖 " + effectiveCoverageText + "，来源："
                    + coverage.source() + "。" + acquisitionText + goldenCrossNote;
        }
        return QUOTE_NOTE + " 本轮有效行情覆盖不足 " + effectiveCoverageText + "，缺失 "
                + coverage.missingCount()
                + " 只；页面保留研究结果，但覆盖率或点时语义未通过时关闭短线执行动作。来源："
                + coverage.source() + "。" + acquisitionText + goldenCrossNote;
    }

    private ShortTermMarketFundDirection marketFundDirection(List<EastMoneyQuote> quoteUniverse) {
        try {
            List<EastMoneyIndustryFundFlowSnapshot> snapshots = eastMoneyClient.fetchIndustryFundFlows();
            if (snapshots == null || snapshots.isEmpty()) {
                return ShortTermMarketFundDirection.unavailable("行业资金流为空，今日资金去向暂不可用。");
            }

            List<String> dataGaps = new ArrayList<>();
            LocalDate tradeDate = snapshots.stream()
                    .map(EastMoneyIndustryFundFlowSnapshot::tradeDate)
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDate::compareTo)
                    .orElse(null);
            LocalDate quoteDate = (quoteUniverse == null ? List.<EastMoneyQuote>of() : quoteUniverse).stream()
                    .map(EastMoneyQuote::tradeDate)
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDate::compareTo)
                    .orElse(null);
            if (tradeDate == null) {
                dataGaps.add("行业资金流缺少数据日，无法确认是否为今日快照。");
            } else if (quoteDate != null && !tradeDate.equals(quoteDate)) {
                dataGaps.add("行业资金流数据日 " + tradeDate + " 与行情日 " + quoteDate + " 不一致。");
            }

            long coveredCount = snapshots.stream()
                    .filter(snapshot -> snapshot.mainNetInflow() != null)
                    .count();
            int expectedCount = snapshots.size();
            BigDecimal coverageRatio = BigDecimal.valueOf(coveredCount)
                    .divide(BigDecimal.valueOf(expectedCount), 4, RoundingMode.HALF_UP);
            if (coverageRatio.compareTo(MIN_RELIABLE_INDUSTRY_FUND_FLOW_COVERAGE) < 0) {
                dataGaps.add("行业资金流覆盖不足，当前覆盖率 " + coverageRatio.multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP) + "%。");
            }

            BigDecimal totalAbsoluteFlow = snapshots.stream()
                    .map(EastMoneyIndustryFundFlowSnapshot::mainNetInflow)
                    .filter(java.util.Objects::nonNull)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<ShortTermIndustryFundDirection> directions = snapshots.stream()
                    .filter(snapshot -> snapshot.mainNetInflow() != null)
                    .map(snapshot -> industryFundDirection(snapshot, totalAbsoluteFlow))
                    .toList();
            Instant fetchedAt = snapshots.stream()
                    .map(EastMoneyIndustryFundFlowSnapshot::fetchedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(null);
            String sourceName = snapshots.stream()
                    .map(EastMoneyIndustryFundFlowSnapshot::sourceName)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse("东方财富行业资金流");
            return new ShortTermMarketFundDirection(
                    directions.stream()
                            .filter(direction -> direction.mainNetInflow().compareTo(BigDecimal.ZERO) > 0)
                            .sorted(Comparator.comparing(
                                    ShortTermIndustryFundDirection::mainNetInflow,
                                    Comparator.reverseOrder()))
                            .limit(5)
                            .toList(),
                    directions.stream()
                            .filter(direction -> direction.mainNetInflow().compareTo(BigDecimal.ZERO) < 0)
                            .sorted(Comparator.comparing(ShortTermIndustryFundDirection::mainNetInflow))
                            .limit(3)
                            .toList(),
                    (int) coveredCount,
                    expectedCount,
                    coverageRatio,
                    tradeDate,
                    fetchedAt,
                    sourceName,
                    dataGaps
            );
        } catch (RuntimeException exception) {
            logger.warn("短线右侧行业资金流获取失败，保留扫描结果", exception);
            return ShortTermMarketFundDirection.unavailable(
                    "行业资金流获取失败：" + rootMessage(exception)
            );
        }
    }

    private ShortTermIndustryFundDirection industryFundDirection(
            EastMoneyIndustryFundFlowSnapshot snapshot,
            BigDecimal totalAbsoluteFlow
    ) {
        BigDecimal concentration = BigDecimal.ZERO;
        if (snapshot.mainNetInflow() != null && totalAbsoluteFlow.compareTo(BigDecimal.ZERO) > 0) {
            concentration = snapshot.mainNetInflow().abs()
                    .divide(totalAbsoluteFlow, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return new ShortTermIndustryFundDirection(
                snapshot.code(),
                snapshot.name(),
                snapshot.mainNetInflow(),
                snapshot.mainNetInflowRatio(),
                snapshot.superLargeNetInflow(),
                snapshot.largeNetInflow(),
                snapshot.advancing(),
                snapshot.declining(),
                snapshot.constituentCount(),
                concentration,
                snapshot.sourceUrl()
        );
    }

    private String marketFundDirectionNote(ShortTermMarketFundDirection direction) {
        if (direction == null || (direction.topInflows().isEmpty() && direction.topOutflows().isEmpty())) {
            return " 今日资金去向：行业资金流暂不可用，页面仅展示个股自身资金流证据。";
        }
        String gapNote = direction.dataGaps().isEmpty()
                ? ""
                : " 数据缺口：" + String.join("；", direction.dataGaps()) + "。";
        return " 今日资金去向为独立市场背景，覆盖 " + direction.coveredIndustryCount()
                + "/" + direction.expectedIndustryCount() + " 个行业板块，数据日 "
                + (direction.tradeDate() == null ? "待确认" : direction.tradeDate()) + "。" + gapNote;
    }

    private ShortTermReport extremeRiskOffReport(
            ShortTermRuleSet ruleSet,
            List<EastMoneyQuote> quoteUniverse,
            ShortTermCoverageSnapshot coverage,
            ShortTermMarketSentiment marketSentiment,
            ShortTermMarketRegime marketRegime,
            ShortTermMarketFundDirection marketFundDirection,
            List<ShortTermRiskExclusion> quoteExclusions,
            List<EastMoneyQuote> pointInTimeCoverageQuotes,
            int quotePreselectedCount,
            List<EastMoneyQuote> marketContextUniverse
    ) {
        ShortTermTechnicalReviewCoverage technicalReviewCoverage = ShortTermTechnicalReviewCoverage.of(
                quotePreselectedCount,
                0,
                0
        );
        ShortTermCrossSectionContext crossSectionContext = crossSectionAnalyzer.analyze(
                marketContextUniverse,
                List.of(),
                Map.of()
        ).context();
        String note = quoteNote(coverage, technicalReviewCoverage)
                + " 市场状态触发极端弱市闸门："
                + marketRegime.explanation()
                + " 本轮不拉取K线、不生成短线推荐，等待广度修复后再评估。";
        Instant reportDataCutoffAt = dataCutoffAt(pointInTimeCoverageQuotes, List.of());
        return new ShortTermReport(
                "A 股短线右侧启动池",
                quoteUniverse.size(),
                0,
                0,
                0,
                note,
                tradingClockService.currentSession(),
                List.of(
                        "实盘扫描先校验全市场覆盖率和点时语义，再计算上涨/下跌家数、涨停近似数和跌停近似数。",
                        "当全市场进入极端弱市，短线右侧信号不再用于推荐；强势个股只作为复盘样本，不能输出买入候选。",
                        "该闸门优先级高于热门方向、金叉、量能和财报质量，目的是避免系统在系统性杀跌中被逆势假强骗入。"
                ),
                ruleSet,
                WEIGHT_PROFILE,
                List.of(),
                List.of(),
                marketSentiment,
                marketFundDirection,
                quoteExclusions,
                Map.of(),
                coverage,
                List.of(),
                reportDataCutoffAt,
                Instant.now(),
                technicalReviewCoverage,
                crossSectionContext,
                marketRegime
        );
    }

    private TechnicalCandidate technicalCandidate(EastMoneyQuote quote, List<EastMoneyKLine> klines, ShortTermRuleSet ruleSet) {
        TechnicalContext context = technicalContext(quote, klines, ruleSet);
        BigDecimal technicalScore = technicalScore(context, ruleSet);
        ShortTermCoreSignalScore coreSignalScore = coreSignalScorer.score(
                context.snapshot().goldenCross(),
                context.snapshot().volumeRatio20(),
                quote.changePercent(),
                context.snapshot().momentumQuality(),
                ruleSet.minVolumeRatio()
        );
        ShortTermVolatilityQuality volatilityQuality = volatilityQualityEvaluator.evaluate(
                context.rows(),
                latestPrice(quote, context),
                quote.tradeDate(),
                context.snapshot()
        );
        return new TechnicalCandidate(quote, context, technicalScore, coreSignalScore, volatilityQuality);
    }

    private ScoredShortTerm score(
            TechnicalCandidate item,
            ShortTermFinancialSnapshot financial,
            EastMoneyFundFlowSnapshot fundFlow,
            ShortTermChipSnapshot chip,
            ShortTermRelativeStrength relativeStrength,
            ShortTermIndustryLeadership industryLeadership,
            ShortTermRuleSet ruleSet,
            Map<String, ShortTermHotDirection> hotDirectionMap,
            ShortTermMarketSentiment marketSentiment,
            ShortTermMarketRegime marketRegime
    ) {
        EastMoneyQuote quote = item.quote();
        ShortTermTechnicalSnapshot technical = item.technical().snapshot();
        BigDecimal financialScore = financial == null ? new BigDecimal("40") : financial.qualityScore();
        QuoteFreshnessSnapshot quoteFreshness = quoteFreshnessService.evaluate(quote);
        BigDecimal marketHeatScore = marketHeatScore(quote, hotDirectionMap);
        ShortTermRelativeStrength safeRelativeStrength = relativeStrength == null
                ? ShortTermRelativeStrength.unavailable("相对强度快照缺失，不参与排序")
                : relativeStrength;
        ShortTermIndustryLeadership safeIndustryLeadership = industryLeadership == null
                ? ShortTermIndustryLeadership.unavailable(quote.industry(), "行业成交额横截面缺失，不参与排序")
                : industryLeadership;
        BigDecimal marketHeatContribution = boundedContribution(
                marketHeatScore.subtract(new BigDecimal("60"))
                        .divide(new BigDecimal("20"), 4, RoundingMode.HALF_UP),
                new BigDecimal("2")
        );
        BigDecimal relativeStrengthContribution = zeroIfNull(safeRelativeStrength.contribution());
        BigDecimal industryLeadershipContribution = zeroIfNull(safeIndustryLeadership.contribution());
        BigDecimal crossSectionAdjustment = boundedContribution(
                marketHeatContribution
                        .add(relativeStrengthContribution)
                        .add(industryLeadershipContribution),
                new BigDecimal("8")
        );
        BigDecimal riskPenalty = riskPenalty(quote, technical, financial, ruleSet);
        ShortTermCoreSignalScore coreSignalScore = item.coreSignalScore();
        BigDecimal finalScore = coreSignalScore.finalScore();
        StageAdjustedScore stageScore = stageAdjustedCoreScore(
                finalScore,
                technical.goldenCross(),
                technical.supportReversal()
        );
        ShortTermSupplyDemandScore supplyDemand = supplyDemandScorer.score(
                fundFlow,
                quote.tradeDate(),
                technical,
                stageScore.rankingScore(),
                chip,
                chipSettings.activationMode()
        );
        ShortTermVolatilityQuality volatilityQuality = item.volatilityQuality() == null
                ? ShortTermVolatilityQuality.unavailable("波动质量快照缺失，不参与排序")
                : item.volatilityQuality();
        BigDecimal volatilityContribution = zeroIfNull(volatilityQuality.contribution());
        BigDecimal rankingScore = clamp(supplyDemand.rankingScore()
                .add(crossSectionAdjustment)
                .add(volatilityContribution));
        ActionDecision decision = decide(
                quote,
                technical,
                financial,
                quoteFreshness,
                marketSentiment,
                marketRegime,
                item.technicalScore(),
                coreSignalScore.volumeScore(),
                stageScore.rankingScore(),
                ruleSet
        );
        ShortTermSignalProfile signalProfile = signalProfileResolver.resolve(technical, volatilityQuality);
        BigDecimal price = latestPrice(quote, item.technical());
        ShortTermCandidate candidate = new ShortTermCandidate(
                0,
                quote.symbol(),
                quote.name(),
                quote.market(),
                quote.industry(),
                price,
                quote.changePercent(),
                pe(quote),
                quote.pbRatio(),
                quote.amount(),
                quoteFreshness,
                phase(technical, decision),
                phaseLabel(technical, decision),
                decision.action(),
                decision.actionLabel(),
                reason(quote, technical, financial, decision),
                todayAdvice(decision, quote, technical, finalScore, ruleSet),
                pendingTailSignal(),
                new ShortTermScoreBreakdown(
                        item.technicalScore(),
                        coreSignalScore.goldenCrossScore(),
                        coreSignalScore.volumeScore(),
                        coreSignalScore.turnoverScore(),
                        coreSignalScore.closeStrengthScore(),
                        supportReversalScore(technical),
                        marketHeatScore,
                        financialScore,
                        riskPenalty,
                        finalScore,
                        stageScore.adjustment(),
                        supplyDemand.mainNetInflowRatio(),
                        supplyDemand.largeOrderNetInflowRatio(),
                        supplyDemand.buyPressureScore(),
                        supplyDemand.fundFlowAdjustment(),
                        supplyDemand.overheadPressureReliefScore(),
                        supplyDemand.technicalRankingScore(),
                        supplyDemand.v2RankingScore(),
                        supplyDemand.chipContributionScore(),
                        supplyDemand.v3RankingScore(),
                        null,
                        null,
                        null,
                        scale(relativeStrengthContribution),
                        scale(industryLeadershipContribution),
                        scale(marketHeatContribution),
                        scale(crossSectionAdjustment),
                        rankingScore,
                        scale(volatilityContribution),
                        scale(rankingScore.subtract(supplyDemand.technicalRankingScore()))
                ),
                technical,
                financial,
                buyZoneLow(price, technical),
                buyZoneHigh(price, technical, ruleSet),
                stopPrice(price, technical),
                supplyDemandStrengths(
                        strengths(quote, technical, financial),
                        supplyDemand
                ),
                supplyDemandRisks(
                        risks(quote, technical, financial, ruleSet, quoteFreshness),
                        supplyDemand
                ),
                entryRules(decision, ruleSet),
                exitRules(ruleSet),
                pendingEvidenceCompleteness(quote, technical, financial, quoteFreshness),
                supplyDemandEvidence(
                        evidence(quote, technical, financial, item.technical(), quoteFreshness, ruleSet),
                        fundFlow,
                        supplyDemand
                ),
                null,
                chip,
                safeRelativeStrength,
                safeIndustryLeadership,
                volatilityQuality,
                signalProfile
        );
        return new ScoredShortTerm(candidate);
    }

    private StageAdjustedScore stageAdjustedCoreScore(
            BigDecimal rawScore,
            ShortTermGoldenCrossSnapshot goldenCross,
            ShortTermSupportReversalSignal supportReversal
    ) {
        BigDecimal score = rawScore == null ? new BigDecimal("30") : rawScore;
        BigDecimal rankingScore = score;
        if (goldenCross != null && goldenCross.confirmedRecent()) {
            rankingScore = clamp(score.max(new BigDecimal("85")));
        } else if (goldenCross != null && goldenCross.watchLayer()) {
            rankingScore = clamp(score.min(new BigDecimal("84")));
        }
        if (supportReversal != null && supportReversal.confirmed() && supportReversal.score() != null) {
            rankingScore = clamp(rankingScore.max(supportReversal.score().min(new BigDecimal("86"))));
        }
        BigDecimal finalRawScore = clamp(score);
        return new StageAdjustedScore(
                finalRawScore,
                scale(rankingScore.subtract(finalRawScore)),
                rankingScore
        );
    }

    private TechnicalContext technicalContext(EastMoneyQuote quote, List<EastMoneyKLine> klines, ShortTermRuleSet ruleSet) {
        long futureBarCount = klines == null || quote.tradeDate() == null ? 0 : klines.stream()
                .filter(kline -> kline != null && kline.tradeDate() != null)
                .filter(kline -> kline.tradeDate().isAfter(quote.tradeDate()))
                .count();
        List<EastMoneyKLine> sorted = klines == null ? List.of() : klines.stream()
                .filter(kline -> kline.close() != null && kline.tradeDate() != null)
                .filter(kline -> quote.tradeDate() == null || !kline.tradeDate().isAfter(quote.tradeDate()))
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        EastMoneyKLine last = sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
        BigDecimal close = latestClose(quote, last);
        boolean latestBarCompleted = last != null && tradingClockService.isCompletedDailyBar(last.tradeDate());
        ShortTermTechnicalSignalEvaluation evaluation = technicalSignalEvaluator.evaluate(
                sorted,
                close,
                quote.tradeDate(),
                quote.volume(),
                latestBarCompleted,
                ruleSet
        );
        ShortTermMomentumQuality momentumQuality = momentumQualityEvaluator.evaluate(
                quote,
                evaluation.rows(),
                close,
                latestBarCompleted
        );
        List<String> dataGaps = new ArrayList<>(evaluation.dataGaps());
        if (futureBarCount > 0) {
            dataGaps.add("已剔除 " + futureBarCount + " 根晚于行情截止日的未来K线");
        }
        dataGaps.addAll(momentumQuality.dataGaps());
        ShortTermTechnicalSnapshot snapshot = evaluation.snapshot().withMomentumQuality(momentumQuality);
        ShortTermSupportReversalSignal supportReversal = supportReversalEvaluator.evaluate(
                quote,
                evaluation.rows(),
                close,
                latestBarCompleted,
                snapshot,
                momentumQuality,
                ruleSet.maxDistanceToMa20Percent()
        );
        dataGaps.addAll(supportReversal.dataGaps());
        return new TechnicalContext(
                quote,
                evaluation.rows(),
                snapshot.withSupportReversal(supportReversal),
                evaluation.last(),
                evaluation.previous(),
                dataGaps
        );
    }

    private boolean hasSufficientTechnicalReview(TechnicalCandidate candidate) {
        if (candidate == null || candidate.technical() == null || candidate.technical().snapshot() == null) {
            return false;
        }
        ShortTermTechnicalSnapshot snapshot = candidate.technical().snapshot();
        return snapshot.tradeDate() != null
                && snapshot.ma5() != null
                && snapshot.ma10() != null
                && snapshot.ma20() != null
                && candidate.technical().rows() != null
                && candidate.technical().rows().size() >= 20;
    }

    private ShortTermFinancialSnapshot financialSnapshot(String symbol) {
        try {
            List<EastMoneyAnnualIndicator> history = eastMoneyClient.fetchAnnualIndicatorHistory(symbol, 3);
            if (history.isEmpty()) {
                return missingFinancial("最近年报指标暂不可用");
            }
            EastMoneyAnnualIndicator latest = history.get(0);
            int positiveCashFlowYears = (int) history.stream()
                    .filter(indicator -> indicator.operatingCashFlowPerShare() != null
                            && indicator.operatingCashFlowPerShare().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            BigDecimal averageRoe = average(history.stream()
                    .map(EastMoneyAnnualIndicator::roe)
                    .toList());
            List<String> gaps = new ArrayList<>();
            if (latest.roe() == null) {
                gaps.add("ROE 缺失");
            }
            if (latest.operatingCashFlowPerShare() == null) {
                gaps.add("经营现金流/股缺失");
            }
            if (latest.revenueGrowth() == null) {
                gaps.add("营收增速缺失");
            }
            BigDecimal qualityScore = financialQualityScore(history);
            String statusLabel = qualityScore.compareTo(new BigDecimal("72")) >= 0
                    ? "财报质量支持"
                    : qualityScore.compareTo(new BigDecimal("58")) >= 0 ? "财报可观察" : "财报偏弱";
            return new ShortTermFinancialSnapshot(
                    latest.reportDate(),
                    latest.dataType(),
                    latest.roe(),
                    latest.operatingCashFlowPerShare(),
                    latest.grossMargin(),
                    latest.revenueGrowth(),
                    latest.netProfitGrowth(),
                    scale(averageRoe),
                    positiveCashFlowYears,
                    qualityScore,
                    statusLabel,
                    gaps
            );
        } catch (RuntimeException exception) {
            logger.warn("短线右侧财报指标获取失败：{}", symbol, exception);
            return missingFinancial("最近年报指标获取失败：" + rootMessage(exception));
        }
    }

    private ShortTermFinancialSnapshot missingFinancial(String gap) {
        return new ShortTermFinancialSnapshot(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                new BigDecimal("40"),
                "财报待复核",
                List.of(gap)
        );
    }

    private BigDecimal technicalScore(TechnicalContext context, ShortTermRuleSet ruleSet) {
        ShortTermTechnicalSnapshot snapshot = context.snapshot();
        if (snapshot.tradeDate() == null) {
            return new BigDecimal("30");
        }
        BigDecimal score = new BigDecimal("42");
        BigDecimal close = latestPrice(context.quote(), context);
        if (snapshot.ma20() != null && close.compareTo(snapshot.ma20()) > 0) {
            score = score.add(new BigDecimal("16"));
        }
        if (snapshot.ma60() != null && close.compareTo(snapshot.ma60()) > 0) {
            score = score.add(new BigDecimal("8"));
        }
        if (snapshot.goldenCross() != null) {
            score = score.add(switch (snapshot.goldenCross().state()) {
                case "CONFIRMED" -> new BigDecimal("14");
                case "APPROACHING" -> new BigDecimal("8");
                case "FORMING" -> new BigDecimal("6");
                case "ESTABLISHED" -> new BigDecimal("4");
                default -> BigDecimal.ZERO;
            });
        }
        if (snapshot.ma20SlopePercent() != null && snapshot.ma20SlopePercent().compareTo(BigDecimal.ZERO) >= 0) {
            score = score.add(new BigDecimal("8"));
        } else if (snapshot.ma20SlopePercent() != null && snapshot.ma20SlopePercent().compareTo(new BigDecimal("-1.00")) < 0) {
            score = score.subtract(new BigDecimal("12"));
        }
        if (snapshot.distanceToMa20Percent() != null
                && snapshot.distanceToMa20Percent().compareTo(BigDecimal.ZERO) >= 0
                && snapshot.distanceToMa20Percent().compareTo(ruleSet.maxDistanceToMa20Percent()) <= 0) {
            score = score.add(new BigDecimal("14"));
        }
        if (snapshot.breakoutFromPreviousHigh20Percent() != null
                && snapshot.breakoutFromPreviousHigh20Percent().compareTo(BigDecimal.ZERO) >= 0
                && snapshot.breakoutFromPreviousHigh20Percent().compareTo(new BigDecimal("6")) <= 0) {
            score = score.add(new BigDecimal("8"));
        }
        if (snapshot.previousRange20Percent() != null
                && snapshot.previousRange20Percent().compareTo(new BigDecimal("4")) >= 0
                && snapshot.previousRange20Percent().compareTo(new BigDecimal("18")) <= 0) {
            score = score.add(new BigDecimal("4"));
        }
        if (snapshot.rangePosition60() != null
                && snapshot.rangePosition60().compareTo(new BigDecimal("35")) >= 0
                && snapshot.rangePosition60().compareTo(new BigDecimal("82")) <= 0) {
            score = score.add(new BigDecimal("12"));
        }
        if (snapshot.rangePosition120() != null
                && snapshot.rangePosition120().compareTo(new BigDecimal("35")) >= 0
                && snapshot.rangePosition120().compareTo(new BigDecimal("82")) <= 0) {
            score = score.add(new BigDecimal("8"));
        }
        if (snapshot.drawdownFrom120HighPercent() != null
                && snapshot.drawdownFrom120HighPercent().compareTo(new BigDecimal("5")) >= 0
                && snapshot.drawdownFrom120HighPercent().compareTo(new BigDecimal("35")) <= 0) {
            score = score.add(new BigDecimal("8"));
        }
        if ("右侧早期确认".equals(snapshot.rightSideSignal())) {
            score = score.add(new BigDecimal("10"));
        } else if ("右侧早期观察".equals(snapshot.rightSideSignal())) {
            score = score.add(new BigDecimal("6"));
        }
        if (snapshot.consecutiveAboveMa20Days() > 12) {
            score = score.subtract(new BigDecimal("8"));
        }
        if (snapshot.distanceToMa20Percent() != null
                && snapshot.distanceToMa20Percent().compareTo(ruleSet.maxDistanceToMa20Percent().add(new BigDecimal("3"))) > 0) {
            score = score.subtract(new BigDecimal("16"));
        }
        if (snapshot.rangePosition120() != null && snapshot.rangePosition120().compareTo(new BigDecimal("88")) > 0) {
            score = score.subtract(new BigDecimal("14"));
        }
        if (snapshot.breakoutFromPreviousHigh20Percent() != null
                && snapshot.breakoutFromPreviousHigh20Percent().compareTo(new BigDecimal("8")) > 0) {
            score = score.subtract(new BigDecimal("8"));
        }
        return clamp(score);
    }

    private BigDecimal financialQualityScore(List<EastMoneyAnnualIndicator> history) {
        EastMoneyAnnualIndicator latest = history.get(0);
        BigDecimal score = new BigDecimal("44");
        if (latest.roe() != null) {
            if (latest.roe().compareTo(ROE_STRONG) >= 0) {
                score = score.add(new BigDecimal("22"));
            } else if (latest.roe().compareTo(ROE_ACCEPTABLE) >= 0) {
                score = score.add(new BigDecimal("16"));
            } else if (latest.roe().compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(new BigDecimal("8"));
            } else {
                score = score.subtract(new BigDecimal("16"));
            }
        }
        if (latest.operatingCashFlowPerShare() != null) {
            score = score.add(latest.operatingCashFlowPerShare().compareTo(BigDecimal.ZERO) > 0 ? new BigDecimal("14") : new BigDecimal("-12"));
        }
        if (latest.grossMargin() != null) {
            score = score.add(latest.grossMargin().compareTo(GROSS_MARGIN_STRONG) >= 0
                    ? new BigDecimal("10")
                    : latest.grossMargin().compareTo(GROSS_MARGIN_ACCEPTABLE) >= 0 ? new BigDecimal("6") : BigDecimal.ZERO);
        }
        if (latest.revenueGrowth() != null) {
            score = score.add(latest.revenueGrowth().compareTo(BigDecimal.ZERO) >= 0 ? new BigDecimal("8") : new BigDecimal("-8"));
        }
        if (latest.netProfitGrowth() != null) {
            score = score.add(latest.netProfitGrowth().compareTo(BigDecimal.ZERO) >= 0 ? new BigDecimal("10") : new BigDecimal("-10"));
        }
        long positiveCashFlowYears = history.stream()
                .filter(indicator -> indicator.operatingCashFlowPerShare() != null
                        && indicator.operatingCashFlowPerShare().compareTo(BigDecimal.ZERO) > 0)
                .count();
        if (positiveCashFlowYears >= 2) {
            score = score.add(new BigDecimal("6"));
        }
        BigDecimal averageRoe = average(history.stream().map(EastMoneyAnnualIndicator::roe).toList());
        if (averageRoe != null && averageRoe.compareTo(ROE_ACCEPTABLE) >= 0) {
            score = score.add(new BigDecimal("6"));
        }
        return clamp(score);
    }

    private BigDecimal riskPenalty(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            ShortTermRuleSet ruleSet
    ) {
        BigDecimal penalty = BigDecimal.ZERO;
        if (quote.changePercent() != null && quote.changePercent().compareTo(ruleSet.maxEntryRisePercent()) > 0) {
            penalty = penalty.add(new BigDecimal("16"));
        }
        if (technical.distanceToMa20Percent() != null
                && technical.distanceToMa20Percent().compareTo(ruleSet.maxDistanceToMa20Percent()) > 0) {
            penalty = penalty.add(new BigDecimal("14"));
        }
        if (technical.rangePosition120() != null && technical.rangePosition120().compareTo(new BigDecimal("88")) > 0) {
            penalty = penalty.add(new BigDecimal("12"));
        }
        if (technical.volumeRatio20() != null && technical.volumeRatio20().compareTo(new BigDecimal("4.20")) > 0) {
            penalty = penalty.add(new BigDecimal("10"));
        }
        if (technical.ma20SlopePercent() != null && technical.ma20SlopePercent().compareTo(new BigDecimal("-0.80")) < 0) {
            penalty = penalty.add(new BigDecimal("8"));
        }
        if (technical.breakoutFromPreviousHigh20Percent() != null
                && technical.breakoutFromPreviousHigh20Percent().compareTo(new BigDecimal("8")) > 0) {
            penalty = penalty.add(new BigDecimal("8"));
        }
        if (financial != null && financial.qualityScore().compareTo(ruleSet.minFinancialScore()) < 0) {
            penalty = penalty.add(new BigDecimal("8"));
        }
        return penalty.min(new BigDecimal("40"));
    }

    private ActionDecision decide(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            QuoteFreshnessSnapshot quoteFreshness,
            ShortTermMarketSentiment marketSentiment,
            ShortTermMarketRegime marketRegime,
            BigDecimal technicalScore,
            BigDecimal volumeScore,
            BigDecimal finalScore,
            ShortTermRuleSet ruleSet
    ) {
        if (quoteFreshness.blocksRealtimeDecision()) {
            return new ActionDecision("DATA_REVIEW", quoteFreshness.statusLabel());
        }
        boolean hasFinancial = financial != null && financial.reportDate() != null;
        boolean financialHardRisk = hasSeriousFinancialRedFlag(financial);
        boolean rightEarly = "右侧早期确认".equals(technical.rightSideSignal()) || "右侧早期观察".equals(technical.rightSideSignal());
        boolean confirmedRecentGoldenCross = technical.goldenCross() != null
                && technical.goldenCross().confirmedRecent();
        boolean goldenCrossWatch = technical.goldenCross() != null && technical.goldenCross().watchLayer();
        boolean volumeConfirmed = isPrimaryVolumeConfirmed(quote, technical, ruleSet);
        boolean volumeObservable = quote.changePercent() != null
                && quote.changePercent().compareTo(BigDecimal.ZERO) > 0
                && technical.volumeRatio20() != null
                && technical.volumeRatio20().compareTo(BigDecimal.ONE) >= 0;
        ShortTermMomentumQuality momentum = technical.momentumQuality() == null
                ? ShortTermMomentumQuality.unavailable()
                : technical.momentumQuality();
        boolean turnoverPreferred = "PREFERRED".equals(momentum.turnoverBand());
        boolean chaseRisk = isChaseRisk(quote, technical, ruleSet);
        boolean crowdedSentiment = "高潮".equals(marketSentiment.phase());
        boolean regimeLightTrialOnly = marketRegime != null && marketRegime.lightTrialOnly();
        boolean marketRiskOff = marketRegime != null && marketRegime.riskOff()
                || "行情覆盖不足".equals(marketSentiment.phase());
        if (marketRiskOff) {
            return new ActionDecision("MARKET_RISK_WAIT", "情绪风险等待");
        }
        if (!hasFinancial) {
            return new ActionDecision("DATA_REVIEW", "财报质量复核");
        }
        if (financialHardRisk) {
            return new ActionDecision("DATA_REVIEW", "基本面红旗复核");
        }
        ShortTermSupportReversalSignal supportReversal = technical.supportReversal();
        if (supportReversal != null && supportReversal.confirmed()) {
            return new ActionDecision("SUPPORT_REVERSAL_LIGHT_TRIAL", "长下影承接-轻仓");
        }
        if ((rightEarly || "右侧已拉开".equals(technical.rightSideSignal()) || goldenCrossWatch) && chaseRisk) {
            return new ActionDecision("WAIT_PULLBACK", "右侧已动-等回踩");
        }
        if (momentum.extremeUpperShadow()) {
            return new ActionDecision("WATCH_RIGHT_SIDE", "长上影观察");
        }
        if (!turnoverPreferred) {
            return new ActionDecision("WATCH_RIGHT_SIDE", "换手区间观察");
        }
        boolean rightEarlyStrongEnough = rightEarly && confirmedRecentGoldenCross
                && technicalScore.compareTo(new BigDecimal("65")) >= 0
                && volumeConfirmed && volumeScore.compareTo(new BigDecimal("78")) >= 0
                && !chaseRisk
                && "右侧早期确认".equals(technical.rightSideSignal());
        if (rightEarlyStrongEnough && regimeLightTrialOnly) {
            return new ActionDecision("REGIME_LIGHT_TRIAL", "市场状态-轻仓");
        }
        if (rightEarlyStrongEnough && !crowdedSentiment) {
            return new ActionDecision("RIGHT_EARLY_ADD", "右侧早期-分批");
        }
        if (rightEarlyStrongEnough && crowdedSentiment) {
            return new ActionDecision("RIGHT_EARLY_LIGHT_TRIAL", "高潮轻仓试错");
        }
        if (goldenCrossWatch) {
            if (!volumeObservable) {
                return new ActionDecision("WAIT_CONFIRM", "量能确认不足");
            }
            return new ActionDecision("WATCH_RIGHT_SIDE", "金叉观察");
        }
        if (rightEarly && finalScore.compareTo(new BigDecimal("62")) >= 0) {
            return new ActionDecision("WATCH_RIGHT_SIDE", "右侧观察");
        }
        if (!hasFinancial || technical.tradeDate() == null) {
            return new ActionDecision("DATA_REVIEW", "数据复核");
        }
        return new ActionDecision("WAIT_CONFIRM", "等待确认");
    }

    private boolean isPrimaryVolumeConfirmed(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermRuleSet ruleSet
    ) {
        return quote.changePercent() != null
                && quote.changePercent().compareTo(BigDecimal.ZERO) > 0
                && technical.volumeRatio20() != null
                && technical.volumeRatio20().compareTo(ruleSet.minVolumeRatio()) >= 0
                && technical.volumeRatio20().compareTo(new BigDecimal("3.20")) <= 0;
    }

    private boolean hasSeriousFinancialRedFlag(ShortTermFinancialSnapshot financial) {
        if (financial == null || financial.reportDate() == null) {
            return false;
        }
        boolean negativeReturnsAndCash = financial.roe() != null
                && financial.roe().compareTo(BigDecimal.ZERO) <= 0
                && financial.operatingCashFlowPerShare() != null
                && financial.operatingCashFlowPerShare().compareTo(BigDecimal.ZERO) <= 0;
        boolean revenueAndProfitDeteriorating = financial.revenueGrowth() != null
                && financial.revenueGrowth().compareTo(BigDecimal.ZERO) < 0
                && financial.netProfitGrowth() != null
                && financial.netProfitGrowth().compareTo(new BigDecimal("-0.30")) < 0;
        return financial.qualityScore().compareTo(new BigDecimal("35")) < 0
                || negativeReturnsAndCash
                || revenueAndProfitDeteriorating;
    }

    private ShortTermMarketSentiment marketSentiment(
            List<EastMoneyQuote> quotes,
            ShortTermCoverageSnapshot coverage
    ) {
        if (!coverage.executionReliable()) {
            return new ShortTermMarketSentiment(
                    "行情覆盖不足",
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    0,
                    0,
                    0,
                    0,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    "行情仅覆盖 " + coverage.fetchedCount() + "/" + coverage.expectedCount()
                            + "，或报价点时语义未通过，不能代表全市场实时广度，本轮关闭短线执行动作。"
            );
        }
        int advancing = 0;
        int declining = 0;
        int limitUpLike = 0;
        int limitDownLike = 0;
        for (EastMoneyQuote quote : quotes) {
            BigDecimal change = quote.changePercent();
            if (change == null) continue;
            if (change.compareTo(BigDecimal.ZERO) > 0) advancing++;
            if (change.compareTo(BigDecimal.ZERO) < 0) declining++;
            if (AsharePriceLimitRule.isLimitUpLike(quote.symbol(), change)) limitUpLike++;
            if (AsharePriceLimitRule.isLimitDownLike(quote.symbol(), change)) limitDownLike++;
        }
        int total = advancing + declining;
        BigDecimal breadth = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(advancing * 100L).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        StructuralHotCluster hotCluster = structuralHotCluster(quotes);
        String phase;
        if (isExtremeSelloff(total, declining, limitDownLike)) {
            phase = "极端退潮";
        } else if (limitDownLike >= 30 || (breadth.compareTo(new BigDecimal("35")) < 0 && limitDownLike > limitUpLike * 2)) {
            phase = "退潮";
        } else if (breadth.compareTo(new BigDecimal("45")) < 0 && hotCluster.available()) {
            phase = "结构性行情";
        } else if (limitUpLike >= 80 && breadth.compareTo(new BigDecimal("65")) >= 0) {
            phase = "高潮";
        } else if (breadth.compareTo(new BigDecimal("55")) >= 0 && limitUpLike > limitDownLike) {
            phase = "发酵";
        } else if (breadth.compareTo(new BigDecimal("45")) >= 0) {
            phase = "修复";
        } else {
            phase = "冰点/混沌";
        }
        BigDecimal score = breadth.add(BigDecimal.valueOf(Math.min(limitUpLike, 100) * 0.15))
                .subtract(BigDecimal.valueOf(Math.min(limitDownLike, 100) * 0.20))
                .max(BigDecimal.ZERO).min(new BigDecimal("100"));
        if ("结构性行情".equals(phase)) {
            score = score.max(new BigDecimal("58"));
        }
        String explanation = "极端退潮".equals(phase)
                ? "全市场下跌 " + declining + "/" + total
                + "，上涨占比仅 " + breadth + "%"
                + "，跌停近似数 " + limitDownLike
                + "，系统性抛压过强；实盘短线不推荐，等待跌停扩散收敛和上涨家数修复。"
                : "结构性行情".equals(phase)
                ? "全市场涨跌广度偏弱，但 " + hotCluster.industry() + " 形成热点簇：上涨 "
                + hotCluster.advancingCount() + "/" + hotCluster.sampleCount()
                + "，平均涨幅 " + hotCluster.averageChangePercent() + "%"
                + "，成交额 " + moneyInYi(hotCluster.totalAmount()) + "；这种环境只允许围绕热点方向轻仓试错。"
                : "基于全市场涨跌广度、涨停近似数和跌停近似数；情绪状态只用于仓位和加仓闸门，不单独产生买点。";
        return new ShortTermMarketSentiment(phase, score.setScale(2, RoundingMode.HALF_UP), advancing, declining,
                limitUpLike, limitDownLike, breadth,
                explanation);
    }

    private boolean isExtremeRiskOffMarket(ShortTermMarketSentiment marketSentiment) {
        return marketSentiment != null && "极端退潮".equals(marketSentiment.phase());
    }

    private boolean isExtremeSelloff(int total, int declining, int limitDownLike) {
        if (total < 1000 || declining <= 0) {
            return false;
        }
        BigDecimal decliningPercent = BigDecimal.valueOf(declining * 100L)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        BigDecimal limitDownPercent = BigDecimal.valueOf(limitDownLike * 100L)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return decliningPercent.compareTo(new BigDecimal("80")) >= 0
                && (limitDownLike >= 600 || limitDownPercent.compareTo(new BigDecimal("10")) >= 0);
    }

    private StructuralHotCluster structuralHotCluster(List<EastMoneyQuote> quotes) {
        Map<String, IndustryMomentumStats> stats = new LinkedHashMap<>();
        for (EastMoneyQuote quote : quotes) {
            String industry = quote.industry();
            if (industry == null || industry.isBlank() || quote.changePercent() == null) {
                continue;
            }
            stats.computeIfAbsent(industry.trim(), IndustryMomentumStats::new).add(quote);
        }
        return stats.values().stream()
                .map(IndustryMomentumStats::toCluster)
                .filter(StructuralHotCluster::available)
                .max(Comparator.comparing(StructuralHotCluster::heatScore)
                        .thenComparing(StructuralHotCluster::totalAmount))
                .orElse(StructuralHotCluster.unavailable());
    }

    private ShortTermCoverageSnapshot coverageSnapshot(
            AshareQuoteSnapshot snapshot,
            List<EastMoneyQuote> uniqueMarketQuotes,
            List<EastMoneyQuote> eligibleQuotes,
            LocalDateTime decisionAt,
            boolean requireCompleteUniverse,
            boolean allowClosedMarketCachePreview
    ) {
        List<EastMoneyQuote> safeUniqueQuotes = uniqueMarketQuotes == null ? List.of() : uniqueMarketQuotes;
        int rawFetchedCount = safeUniqueQuotes.size();
        List<EastMoneyQuote> usableRawQuotes = safeUniqueQuotes.stream()
                .filter(this::hasUsablePrice)
                .toList();
        int excludedNoPriceCount = rawFetchedCount - usableRawQuotes.size();
        if (snapshot == null || snapshot.expectedCount() <= 0) {
            return new ShortTermCoverageSnapshot(
                    0,
                    eligibleQuotes == null ? 0 : eligibleQuotes.size(),
                    0,
                    BigDecimal.ZERO,
                    false,
                    snapshot == null || snapshot.source() == null || snapshot.source().isBlank()
                            ? "未知"
                            : snapshot.source(),
                    snapshot == null ? null : snapshot.fetchedAt(),
                    0,
                    rawFetchedCount,
                    excludedNoPriceCount,
                    false
            );
        }
        int rawExpectedCount = Math.max(0, snapshot.expectedCount());
        int effectiveExpectedCount = Math.max(0, rawExpectedCount - excludedNoPriceCount);
        int effectiveFetchedCount = eligibleQuotes == null ? 0 : eligibleQuotes.size();
        int effectiveMissingCount = Math.max(0, effectiveExpectedCount - effectiveFetchedCount);
        BigDecimal ratio = effectiveExpectedCount == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(effectiveFetchedCount)
                .divide(BigDecimal.valueOf(effectiveExpectedCount), 4, RoundingMode.HALF_UP);
        Instant decisionInstant = decisionAt.atZone(SHANGHAI).toInstant();
        boolean validSource = snapshot.source() != null && !snapshot.source().isBlank();
        boolean freshSnapshot = snapshot.fetchedAt() != null
                && !snapshot.fetchedAt().isBefore(decisionInstant.minus(automationSettings.freshness()))
                && !snapshot.fetchedAt().isAfter(decisionInstant.plusSeconds(5));
        boolean requestedFullReportedUniverse = rawExpectedCount > 0
                && snapshot.requestedCount() >= rawExpectedCount;
        boolean countsConsistent = snapshot.fetchedCount() == rawFetchedCount
                && rawFetchedCount <= rawExpectedCount
                && excludedNoPriceCount <= rawFetchedCount
                && effectiveFetchedCount <= effectiveExpectedCount;
        boolean allQuotesRespectDecisionAt = usableRawQuotes.stream()
                .allMatch(quote -> quoteAvailableAtDecision(quote, decisionAt, allowClosedMarketCachePreview));
        boolean rawCompletenessRequired = requireCompleteUniverse || requestedFullReportedUniverse;
        boolean reliable = ratio.compareTo(MIN_RELIABLE_MARKET_COVERAGE) >= 0
                && validSource
                && freshSnapshot
                && requestedFullReportedUniverse
                && countsConsistent
                && allQuotesRespectDecisionAt
                && (!rawCompletenessRequired || snapshot.complete());
        return new ShortTermCoverageSnapshot(
                effectiveExpectedCount,
                effectiveFetchedCount,
                effectiveMissingCount,
                ratio,
                reliable,
                validSource ? snapshot.source() : "未知",
                snapshot.fetchedAt(),
                rawExpectedCount,
                rawFetchedCount,
                excludedNoPriceCount,
                snapshot.complete()
        );
    }

    private Instant dataCutoffAt(
            List<EastMoneyQuote> quoteUniverse,
            List<ShortTermCandidate> candidates
    ) {
        Instant quoteCutoff = quoteUniverse.stream()
                .map(EastMoneyQuote::marketTimestamp)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        Instant tailCutoff = candidates.stream()
                .map(ShortTermCandidate::tailSignal)
                .filter(signal -> signal != null && signal.actionableTailWindow())
                .map(this::tailCutoffAt)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (quoteCutoff == null) {
            return tailCutoff;
        }
        if (tailCutoff == null) {
            return quoteCutoff;
        }
        return quoteCutoff.isAfter(tailCutoff) ? quoteCutoff : tailCutoff;
    }

    private Instant tailCutoffAt(ShortTermTailSignal signal) {
        try {
            LocalDate date = LocalDate.parse(signal.tradeDate());
            LocalTime time = LocalTime.parse(signal.latestMinute());
            if (time.isBefore(ACTIONABLE_TAIL_START) || !time.isBefore(ACTIONABLE_TAIL_END_EXCLUSIVE)) {
                return null;
            }
            return LocalDateTime.of(date, time).atZone(SHANGHAI).toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean isChaseRisk(EastMoneyQuote quote, ShortTermTechnicalSnapshot technical, ShortTermRuleSet ruleSet) {
        return quote.changePercent() != null && quote.changePercent().compareTo(ruleSet.maxEntryRisePercent()) > 0
                || technical.distanceToMa20Percent() != null
                && technical.distanceToMa20Percent().compareTo(ruleSet.maxDistanceToMa20Percent()) > 0
                || technical.rangePosition120() != null && technical.rangePosition120().compareTo(new BigDecimal("88")) > 0;
    }

    private TradingAdvice todayAdvice(
            ActionDecision decision,
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            BigDecimal finalScore,
            ShortTermRuleSet ruleSet
    ) {
        if ("RIGHT_EARLY_ADD".equals(decision.action())) {
            return new TradingAdvice(
                    "ADD",
                    "加仓",
                    confidence(finalScore),
                    "右侧早期结构和基本面校验同时通过，可按短线纪律分批试错。",
                    List.of(
                            "股价站上关键均线且距离 20 日线不远。",
                            "量价关系健康，可能是温和放量突破或缩量上涨惜售。",
                            "最近年报质量没有明显冲突。"
                    ),
                    List.of(
                            "单票短线仓位不超过计划仓位的 1/3。",
                            "单日涨幅超过 " + ruleSet.maxEntryRisePercent() + "% 后不追第二笔。",
                            "跌破 20 日线或放量长阴时退出试错。"
                    )
            );
        }
        if ("WAIT_PULLBACK".equals(decision.action())) {
            return new TradingAdvice(
                    "WAIT_PULLBACK",
                    "等回踩",
                    confidence(finalScore),
                    "右侧信号已经出现，但当前位置离均线或当日涨幅偏大，短线不适合追。",
                    List.of("趋势可能已经启动。", "追涨风险高于试错收益。"),
                    List.of("观察回踩 5/10/20 日线承接。", "回踩缩量不破再重新评估。")
            );
        }
        if ("RIGHT_EARLY_LIGHT_TRIAL".equals(decision.action())) {
            return new TradingAdvice(
                    "LIGHT_TRIAL",
                    "轻仓试错",
                    Math.min(confidence(finalScore), 74),
                    "市场情绪处于高潮，右侧结构、金叉和量能已经确认，但只能轻仓试错，不能当作强加仓。",
                    List.of(
                            "日线右侧早期确认，且金叉、量能、换手没有硬风险冲突。",
                            "高潮阶段容易次日分化，机会来自跟随强势，风险来自一致性过热。"
                    ),
                    List.of(
                            "单票试错仓位不超过计划短线仓位的 1/5。",
                            "不追第二笔，次日不能继续站稳 5/10/20 日线时退出。",
                            "尾盘从高点回落或跌回均价线下方时取消试错。"
                    )
            );
        }
        if ("REGIME_LIGHT_TRIAL".equals(decision.action())) {
            return new TradingAdvice(
                    "LIGHT_TRIAL",
                    "轻仓试错",
                    Math.min(confidence(finalScore), 72),
                    "市场仍处于修复或拥挤高波动状态，个股右侧结构虽已确认，也只允许轻仓试错。",
                    List.of(
                            "个股金叉、量能与右侧结构已达到原始执行条件。",
                            "市场状态尚未进入有序趋势扩张，动作强度已从加仓降为轻仓。"
                    ),
                    List.of(
                            "单票试错仓位不超过计划短线仓位的 1/5。",
                            "尾盘承接减弱时取消试错，次日不能延续则退出。",
                            "市场状态转为系统性退潮时停止新增短线仓位。"
                    )
            );
        }
        if ("SUPPORT_REVERSAL_LIGHT_TRIAL".equals(decision.action())) {
            ShortTermSupportReversalSignal support = technical.supportReversal();
            return new TradingAdvice(
                    "LIGHT_TRIAL",
                    "轻仓试错",
                    Math.min(confidence(finalScore), 76),
                    "股价微跌但长下影收复关键支撑，属于承接反转试错，不是趋势加仓信号。",
                    List.of(
                            "下影线、收盘位置、支撑收复与量能条件同时通过。",
                            "当前只证明下方存在承接，不能据此推断主力正在做多。"
                    ),
                    List.of(
                            "单票试错仓位不超过计划短线仓位的 1/5。",
                            "跌破本次承接低点或重新失守 " + supportName(support) + " 时退出。",
                            "次日弱开且 30 分钟内不能收回支撑时不补仓。"
                    )
            );
        }
        if ("WATCH_RIGHT_SIDE".equals(decision.action()) || "WATCH_VALUE_RETURN".equals(decision.action())) {
            return new TradingAdvice(
                    "WAIT",
                    "观望",
                    confidence(finalScore),
                    "右侧雏形存在，但量能或突破强度还没有形成强确认，不急于直接买。",
                    List.of("趋势开始转强。", "财报没有明显否决。"),
                    List.of("等待量比重新大于 " + ruleSet.minVolumeRatio() + "。", "等待突破前 20 日高点，或缩量回踩 20 日线不破。")
            );
        }
        return new TradingAdvice(
                "WAIT",
                "观望",
                confidence(finalScore),
                "当前不满足短线右侧早期、量能确认和质量过滤共振条件。",
                List.of("K 线、量能、热门方向或财报至少一项证据不足。"),
                List.of("缺失数据补齐前不作为执行买点。", "只把它放入观察，不做情绪化追单。")
        );
    }

    private ShortTermCandidate enrichTailSignal(ShortTermCandidate candidate) {
        ShortTermTailSignal tailSignal = tailSignal(candidate.symbol(), expectedTailTradeDate(candidate));
        EvidenceCompleteness completeness = evidenceCompleteness(candidate, tailSignal);
        TradingAdvice tailAdjusted = isGoldenCrossWatchLayer(candidate.technical())
                ? candidate.todayAdvice()
                : tailAdjustedAdvice(
                        candidate.todayAdvice(),
                        candidate.action(),
                        tailSignal,
                        hasRecentConfirmedGoldenCross(candidate.technical())
                );
        TradingAdvice adjustedAdvice = evidenceCompletenessService.gateAdvice(
                tailAdjusted,
                completeness
        );
        return new ShortTermCandidate(
                candidate.rank(),
                candidate.symbol(),
                candidate.name(),
                candidate.market(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.changePercent(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.amount(),
                candidate.quoteFreshness(),
                candidate.phase(),
                candidate.phaseLabel(),
                candidate.action(),
                candidate.actionLabel(),
                candidate.reason(),
                adjustedAdvice,
                tailSignal,
                candidate.score(),
                candidate.technical(),
                candidate.financial(),
                candidate.buyZoneLow(),
                candidate.buyZoneHigh(),
                candidate.stopPrice(),
                candidate.strengths(),
                candidate.risks(),
                withTailEntryRule(candidate.entryRules()),
                candidate.exitRules(),
                completeness,
                withTailEvidence(candidate.evidence(), tailSignal),
                candidate.tradePlan(),
                candidate.chip(),
                candidate.relativeStrength(),
                candidate.industryLeadership(),
                candidate.volatilityQuality(),
                candidate.signalProfile()
        );
    }

    ShortTermCandidate attachTradePlan(ShortTermCandidate candidate, OvernightRuleSet rules) {
        boolean executableAction = candidate.todayAdvice() != null
                && Set.of("ADD", "LIGHT_TRIAL").contains(candidate.todayAdvice().action());
        boolean freshnessAllowsExecution = candidate.quoteFreshness() != null
                && !candidate.quoteFreshness().blocksRealtimeDecision();
        boolean evidenceAllowsExecution = candidate.evidenceCompleteness() != null
                && candidate.evidenceCompleteness().allowsBuy();
        LocalDate tradeDate = candidate.quoteFreshness() != null
                && candidate.quoteFreshness().tradeDate() != null
                ? candidate.quoteFreshness().tradeDate()
                : candidate.technical() == null ? null : candidate.technical().tradeDate();

        ShortTermTradePlan plan;
        if (executableAction && freshnessAllowsExecution && evidenceAllowsExecution) {
            plan = tradePlanService.create(
                    tradeDate,
                    candidate.latestPrice(),
                    candidate.buyZoneLow(),
                    candidate.buyZoneHigh(),
                    candidate.technical(),
                    rules
            );
        } else {
            List<String> blockedReasons = new ArrayList<>();
            if (!executableAction) {
                blockedReasons.add("当前建议不是可执行的加仓或轻仓试错动作");
            }
            if (!freshnessAllowsExecution) {
                blockedReasons.add("实时行情新鲜度未通过，交易计划不可执行");
            }
            if (!evidenceAllowsExecution) {
                blockedReasons.add("证据完整度未通过，交易计划不可执行");
            }
            plan = tradePlanService.blocked(
                    tradeDate,
                    candidate.latestPrice(),
                    candidate.buyZoneLow(),
                    candidate.buyZoneHigh(),
                    candidate.technical(),
                    rules,
                    blockedReasons
            );
        }
        return copyWithTradePlan(candidate, plan);
    }

    private ShortTermCandidate applyCoverageExecutionGate(
            ShortTermCandidate candidate,
            ShortTermCoverageSnapshot coverage
    ) {
        if (coverage != null
                && coverage.executionReliable()
                && coverage.coverageRatio() != null
                && coverage.coverageRatio().compareTo(MIN_RELIABLE_MARKET_COVERAGE) >= 0) {
            return candidate;
        }
        if (candidate.todayAdvice() == null
                || !Set.of("ADD", "LIGHT_TRIAL").contains(candidate.todayAdvice().action())) {
            return candidate;
        }
        BigDecimal ratio = coverage == null || coverage.coverageRatio() == null
                ? null
                : coverage.coverageRatio().multiply(new BigDecimal("100"));
        String coverageText = ratio == null ? "待确认" : ratio.setScale(2, RoundingMode.HALF_UP) + "%";
        TradingAdvice blockedAdvice = new TradingAdvice(
                "WAIT",
                "数据阻断",
                Math.min(40, candidate.todayAdvice() == null ? 40 : candidate.todayAdvice().confidence()),
                "全市场有效行情覆盖率为 " + coverageText + "，未通过 95% 执行闸门；候选仅供研究，不得据此新建仓位。",
                merge(
                        List.of("市场行情覆盖或点时完整性未通过。"),
                        candidate.todayAdvice() == null ? List.of() : candidate.todayAdvice().reasons()
                ),
                merge(
                        List.of("等待有效行情覆盖率达到 95% 且数据时点通过后重新扫描。"),
                        candidate.todayAdvice() == null ? List.of() : candidate.todayAdvice().riskControls()
                )
        );
        return new ShortTermCandidate(
                candidate.rank(), candidate.symbol(), candidate.name(), candidate.market(), candidate.industry(),
                candidate.latestPrice(), candidate.changePercent(), candidate.peTtm(), candidate.pbRatio(),
                candidate.amount(), candidate.quoteFreshness(),
                candidate.phase(), candidate.phaseLabel(), "DATA_REVIEW", "数据阻断",
                "全市场有效行情覆盖未通过 95% 执行闸门；" + candidate.reason(),
                blockedAdvice, candidate.tailSignal(), candidate.score(), candidate.technical(), candidate.financial(),
                candidate.buyZoneLow(), candidate.buyZoneHigh(), candidate.stopPrice(), candidate.strengths(),
                candidate.risks(), candidate.entryRules(), candidate.exitRules(), candidate.evidenceCompleteness(),
                candidate.evidence(), candidate.tradePlan(), candidate.chip(), candidate.relativeStrength(),
                candidate.industryLeadership(), candidate.volatilityQuality(), candidate.signalProfile()
        );
    }

    private ShortTermCandidate copyWithTradePlan(ShortTermCandidate candidate, ShortTermTradePlan plan) {
        return new ShortTermCandidate(
                candidate.rank(),
                candidate.symbol(),
                candidate.name(),
                candidate.market(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.changePercent(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.amount(),
                candidate.quoteFreshness(),
                candidate.phase(),
                candidate.phaseLabel(),
                candidate.action(),
                candidate.actionLabel(),
                candidate.reason(),
                candidate.todayAdvice(),
                candidate.tailSignal(),
                candidate.score(),
                candidate.technical(),
                candidate.financial(),
                candidate.buyZoneLow(),
                candidate.buyZoneHigh(),
                candidate.stopPrice(),
                candidate.strengths(),
                candidate.risks(),
                candidate.entryRules(),
                candidate.exitRules(),
                candidate.evidenceCompleteness(),
                candidate.evidence(),
                plan,
                candidate.chip(),
                candidate.relativeStrength(),
                candidate.industryLeadership(),
                candidate.volatilityQuality(),
                candidate.signalProfile()
        );
    }

    private LocalDate expectedTailTradeDate(ShortTermCandidate candidate) {
        if (candidate.quoteFreshness() != null && candidate.quoteFreshness().tradeDate() != null) {
            return candidate.quoteFreshness().tradeDate();
        }
        return candidate.technical() == null ? null : candidate.technical().tradeDate();
    }

    private ShortTermTailSignal tailSignal(String symbol, LocalDate expectedTradeDate) {
        try {
            List<EastMoneyIntradayPoint> points = eastMoneyClient.fetchIntradayTrends(symbol).stream()
                    .filter(point -> point.minute() != null && point.close() != null)
                    .sorted(Comparator.comparing(EastMoneyIntradayPoint::minute))
                    .toList();
            return evaluateTailSignal(points, expectedTradeDate);
        } catch (RuntimeException exception) {
            logger.warn("尾盘分时数据获取失败：{}", symbol, exception);
            return new ShortTermTailSignal(
                    "UNAVAILABLE",
                    "分时缺失",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("35"),
                    List.of("当天 1 分钟分时数据暂不可用，不能确认 14:45-14:49 可执行尾盘承接。"),
                    List.of("尾盘分时缺失时不执行短线买入。")
            );
        }
    }

    private ShortTermTailSignal evaluateTailSignal(List<EastMoneyIntradayPoint> points, LocalDate expectedTradeDate) {
        if (points.isEmpty()) {
            return new ShortTermTailSignal(
                    "UNAVAILABLE",
                    "分时缺失",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("35"),
                    List.of("当天 1 分钟分时为空，无法判断 14:45-14:49 可执行尾盘承接。"),
                    List.of("没有当天分时证据时不执行短线买入。")
            );
        }
        EastMoneyIntradayPoint latestAvailable = points.get(points.size() - 1);
        if (expectedTradeDate != null) {
            List<EastMoneyIntradayPoint> expectedDayPoints = points.stream()
                    .filter(point -> expectedTradeDate.equals(point.minute().toLocalDate()))
                    .toList();
            if (expectedDayPoints.isEmpty()) {
                return new ShortTermTailSignal(
                        "STALE_TRADING_DAY",
                        "分时非当日",
                        false,
                        latestAvailable.minute().toLocalDate().toString(),
                        latestAvailable.minute().toLocalTime().toString(),
                        money(latestAvailable.close()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("20"),
                        List.of("分时日期为 " + latestAvailable.minute().toLocalDate()
                                + "，与行情交易日 " + expectedTradeDate + " 不一致，不能作为当日尾盘确认。"),
                        List.of("跨交易日分时一律只用于复盘，不形成买入或试仓动作。")
                );
            }
            points = expectedDayPoints;
        }
        LocalDateTime decisionAt = tradingClockService.currentMarketDateTime();
        points = points.stream()
                .filter(point -> !point.minute().toLocalDate().equals(decisionAt.toLocalDate())
                        || !point.minute().isAfter(decisionAt))
                .toList();
        if (points.isEmpty()) {
            return new ShortTermTailSignal(
                    "UNAVAILABLE",
                    "点时分时缺失",
                    false,
                    expectedTradeDate == null ? null : expectedTradeDate.toString(),
                    decisionAt.toLocalTime().toString(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("35"),
                    List.of("截至服务端决策时刻没有可用分时，不能读取随后产生的分钟数据。"),
                    List.of("点时证据缺失时不执行短线买入。")
            );
        }
        EastMoneyIntradayPoint latestAvailableForDay = points.get(points.size() - 1);
        String tradeDate = latestAvailableForDay.minute().toLocalDate().toString();
        String latestAvailableMinute = latestAvailableForDay.minute().toLocalTime().toString();
        if (latestAvailableForDay.minute().toLocalTime().isBefore(ACTIONABLE_TAIL_START)) {
            return new ShortTermTailSignal(
                    "NOT_READY",
                    "等14:45",
                    false,
                    tradeDate,
                    latestAvailableMinute,
                    money(latestAvailableForDay.close()),
                    null,
                    null,
                    null,
                    latestAvailableForDay.averagePrice() == null ? null
                            : scale(percent(latestAvailableForDay.close().subtract(latestAvailableForDay.averagePrice()),
                            latestAvailableForDay.averagePrice())),
                    null,
                    null,
                    new BigDecimal("40"),
                    List.of("当前最新分时到 " + latestAvailableMinute + "，尚未进入 14:45-14:49 可执行尾盘窗口。"),
                    List.of("14:45 前只筛候选，不把盘中涨幅当作买点。", "进入可执行窗口后再复核均价线、回落和成交占比。")
            );
        }

        List<EastMoneyIntradayPoint> tailPoints = points.stream()
                .filter(point -> !point.minute().toLocalTime().isBefore(ACTIONABLE_TAIL_START))
                .filter(point -> point.minute().toLocalTime().isBefore(ACTIONABLE_TAIL_END_EXCLUSIVE))
                .toList();
        if (tailPoints.isEmpty() && isPostCloseFixedPriceMinute(latestAvailableForDay.minute().toLocalTime())) {
            return new ShortTermTailSignal(
                    "POST_CLOSE_FIXED_PRICE",
                    "盘后固定价",
                    false,
                    tradeDate,
                    latestAvailableMinute,
                    money(latestAvailableForDay.close()),
                    null,
                    null,
                    null,
                    latestAvailableForDay.averagePrice() == null ? null
                            : scale(percent(latestAvailableForDay.close().subtract(latestAvailableForDay.averagePrice()),
                            latestAvailableForDay.averagePrice())),
                    null,
                    null,
                    new BigDecimal("38"),
                    List.of("最新分时到 " + latestAvailableMinute + "，属于 15:05-15:30 盘后固定价格区间，不是普通竞价尾盘确认。"),
                    List.of("盘后固定价格不能和普通尾盘买点混用。", "14:57 后数据只用于历史复盘。")
            );
        }
        if (tailPoints.isEmpty()) {
            return new ShortTermTailSignal(
                    "HISTORICAL_ONLY",
                    "仅供复盘",
                    false,
                    tradeDate,
                    latestAvailableMinute,
                    money(latestAvailableForDay.close()),
                    null,
                    null,
                    null,
                    latestAvailableForDay.averagePrice() == null ? null
                            : scale(percent(latestAvailableForDay.close().subtract(latestAvailableForDay.averagePrice()),
                            latestAvailableForDay.averagePrice())),
                    null,
                    null,
                    new BigDecimal("35"),
                    List.of("缺少 14:45-14:49 可执行尾盘分时；14:50 后数据不能反推当日买点。"),
                    List.of("可执行窗口证据缺失时不执行短线买入。")
            );
        }
        EastMoneyIntradayPoint start = tailPoints.get(0);
        EastMoneyIntradayPoint latest = tailPoints.get(tailPoints.size() - 1);
        String latestMinute = latest.minute().toLocalTime().toString();
        BigDecimal tailHigh = tailPoints.stream()
                .map(point -> point.high() == null ? point.close() : point.high())
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(latest.close());
        BigDecimal tailAmount = tailPoints.stream()
                .map(point -> point.amount() == null ? BigDecimal.ZERO : point.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = points.stream()
                .filter(point -> point.minute().toLocalTime().isBefore(ACTIONABLE_TAIL_END_EXCLUSIVE))
                .map(point -> point.amount() == null ? BigDecimal.ZERO : point.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal changeFromActionableTail = scale(percent(latest.close().subtract(start.close()), start.close()));
        BigDecimal drawdown = tailHigh == null ? null : scale(percent(tailHigh.subtract(latest.close()), tailHigh));
        BigDecimal closeVsAverage = latest.averagePrice() == null ? null : scale(percent(latest.close().subtract(latest.averagePrice()), latest.averagePrice()));
        BigDecimal tailAmountRatio = totalAmount.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : scale(tailAmount.multiply(new BigDecimal("100")).divide(totalAmount, 6, RoundingMode.HALF_UP));
        BigDecimal score = tailScore(changeFromActionableTail, drawdown, closeVsAverage, tailAmountRatio, tailPoints.size());
        BigDecimal tailAmountRatioThreshold = tailAmountRatioThreshold(totalAmount);
        String status;
        String label;
        if (tailPoints.size() >= 3
                && gte(changeFromActionableTail, "0.30")
                && lte(drawdown, "1.20")
                && gte(closeVsAverage, "0.00")
                && tailAmountRatio != null
                && tailAmountRatio.compareTo(tailAmountRatioThreshold) >= 0) {
            status = "CONFIRMED";
            label = "尾盘确认";
        } else if (gte(changeFromActionableTail, "0.00")
                && lte(drawdown, "1.80")
                && gte(closeVsAverage, "-0.25")) {
            status = "WATCH";
            label = "尾盘观察";
        } else {
            status = "WEAK";
            label = "尾盘回落";
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("14:45-14:49 涨跌 " + valueText(changeFromActionableTail) + "%，可执行尾盘高点回落 " + valueText(drawdown) + "%。");
        if (points.stream().anyMatch(point ->
                !point.minute().toLocalTime().isBefore(ACTIONABLE_TAIL_END_EXCLUSIVE))) {
            reasons.add("14:50 后分时仅保留为历史观察，未参与本次可执行评分。");
        }
        reasons.add("最新价相对当日均价线 " + valueText(closeVsAverage) + "%，尾盘成交额占比 " + valueText(tailAmountRatio)
                + "%，本票动态确认门槛 " + valueText(tailAmountRatioThreshold) + "%。");
        if ("CONFIRMED".equals(status)) {
            reasons.add("价格、均价线和尾盘成交占比同时满足试错确认。");
        } else if ("WATCH".equals(status)) {
            reasons.add("尾盘没有明显走坏，但强度不足，只能继续观察。");
        } else {
            reasons.add("尾盘回落或均价线承接不足，不适合作为 14:45-14:49 可执行买点。");
        }
        return new ShortTermTailSignal(
                status,
                label,
                true,
                tradeDate,
                latestMinute,
                money(latest.close()),
                money(start.close()),
                changeFromActionableTail,
                drawdown,
                closeVsAverage,
                money(tailAmount),
                tailAmountRatio,
                score,
                reasons,
                List.of("普通股票只用 14:45-14:49 已产生的数据完成尾盘决策。", "尾盘跌回均价线下方或从高点回落超过 1.8% 时不追。", "大成交额股票采用较低尾盘成交占比门槛，但仍必须站稳均价线并保持价格强度。", "14:50 后数据只用于历史复盘，不能反推买点。")
        );
    }

    private BigDecimal tailAmountRatioThreshold(BigDecimal totalAmount) {
        if (totalAmount != null && totalAmount.compareTo(LARGE_TURNOVER_TAIL_AMOUNT) >= 0) {
            return new BigDecimal("3.00");
        }
        if (totalAmount != null && totalAmount.compareTo(MEDIUM_TURNOVER_TAIL_AMOUNT) >= 0) {
            return new BigDecimal("4.50");
        }
        return new BigDecimal("6.00");
    }

    private BigDecimal tailScore(
            BigDecimal changeFromActionableTail,
            BigDecimal drawdown,
            BigDecimal closeVsAverage,
            BigDecimal tailAmountRatio,
            int minuteCount
    ) {
        BigDecimal score = new BigDecimal("50")
                .add(nullToZero(changeFromActionableTail).multiply(new BigDecimal("12")))
                .subtract(nullToZero(drawdown).multiply(new BigDecimal("10")))
                .add(nullToZero(closeVsAverage).multiply(new BigDecimal("8")))
                .add(nullToZero(tailAmountRatio).min(new BigDecimal("18")).multiply(new BigDecimal("0.80")))
                .add(BigDecimal.valueOf(Math.min(minuteCount, 20)).multiply(new BigDecimal("0.30")));
        return scale(clamp(score));
    }

    private TradingAdvice tailAdjustedAdvice(
            TradingAdvice base,
            String candidateAction,
            ShortTermTailSignal tailSignal
    ) {
        return tailAdjustedAdvice(base, candidateAction, tailSignal, true);
    }

    private TradingAdvice tailAdjustedAdvice(
            TradingAdvice base,
            String candidateAction,
            ShortTermTailSignal tailSignal,
            boolean recentConfirmedGoldenCross
    ) {
        LocalDateTime decisionAt = tradingClockService.currentMarketDateTime();
        boolean executableEntryWindow = TradingClockService.SHORT_TERM_ENTRY_CHECKPOINT.equals(
                tradingClockService.shortTermDecisionCheckpoint());
        boolean evidenceAvailableAtDecision = tailEvidenceAvailableAtDecision(tailSignal, decisionAt);
        boolean executableEvidence = executableEntryWindow && evidenceAvailableAtDecision;
        if (executableEvidence
                && "CONFIRMED".equals(tailSignal.status())
                && isRightSideExecutableCandidate(candidateAction)) {
            return new TradingAdvice(
                    "ADD",
                    "加仓",
                    Math.min(90, Math.max(base.confidence(), tailSignal.score().setScale(0, RoundingMode.HALF_UP).intValue())),
                    "右侧结构通过，且当日分时证据不晚于 14:45-14:49 可成交决策时刻，可按纪律小仓试错。",
                    merge(base.reasons(), tailSignal.reasons()),
                    merge(base.riskControls(), tailSignal.riskControls())
            );
        }
        if (isPullbackAdvice(base)) {
            return base;
        }
        if (executableEntryWindow && !evidenceAvailableAtDecision) {
            return new TradingAdvice(
                    "WAIT",
                    "观望",
                    Math.max(35, Math.min(base.confidence(), tailSignal.score().setScale(0, RoundingMode.HALF_UP).intValue())),
                    "尾盘证据不属于当前交易日，或其时间晚于服务端决策时刻，仅保留用于研究和复盘，不可新建短线仓位。",
                    merge(tailSignal.reasons(), base.reasons()),
                    merge(tailSignal.riskControls(), base.riskControls())
            );
        }
        if (executableEvidence
                && shouldLightTrial(base, candidateAction, tailSignal, recentConfirmedGoldenCross)) {
            return new TradingAdvice(
                    "LIGHT_TRIAL",
                    "轻仓试错",
                    lightTrialConfidence(base, tailSignal),
                    lightTrialSummary(base),
                    merge(List.of("日线右侧结构进入观察区。", "当日分时证据不晚于服务端决策时刻，具备小仓验证条件。"), base.reasons()),
                    merge(base.riskControls(), List.of("轻仓试错不超过计划短线仓位的 1/5。", "次日不能继续站稳 5/10/20 日线时退出试错。"))
            );
        }
        if (shouldNextDayWatch(base, candidateAction, tailSignal)) {
            return new TradingAdvice(
                    "NEXT_WATCH",
                    "次日关注",
                    nextWatchConfidence(base),
                    "日线结构值得继续跟踪，但今天尾盘承接没有达到买点，列为次日关注，等回踩或放量再确认。",
                    merge(List.of("右侧形态仍在，但今日分时不支持执行买入。"), base.reasons()),
                    merge(tailSignal.riskControls(), List.of("次日若缩量回踩不破 5/10/20 日线再重新评估。", "次日若高开急拉，不追第一笔。"))
            );
        }
        String summary = switch (tailSignal.status()) {
            case "CONFIRMED" -> "14:45-14:49 尾盘证据已形成，但当前已不在普通股票可成交决策窗口；结果只用于研究，不可新建短线仓位。";
            case "NOT_READY" -> "当前还没有进入 14:45-14:49 可执行尾盘窗口，先保留候选观察。";
            case "POST_CLOSE_FIXED_PRICE" -> "当前数据属于盘后固定价格区间，不能和普通尾盘买点混用，今日先观察。";
            case "WATCH" -> "14:45-14:49 尾盘观察证据不足，结果只用于研究，不可新建短线仓位。";
            case "WEAK" -> "14:45-14:49 分时承接不足或从高点回落，今日不追。";
            case "HISTORICAL_ONLY" -> "只有 14:57 后历史数据，不能反推当日可执行买点。";
            default -> "当天分时数据缺失，无法确认 " + ACTIONABLE_TAIL_LABEL + " 可执行买点。";
        };
        return new TradingAdvice(
                "WAIT",
                "观望",
                Math.max(35, Math.min(base.confidence(), tailSignal.score().setScale(0, RoundingMode.HALF_UP).intValue())),
                summary,
                merge(tailSignal.reasons(), base.reasons()),
                merge(tailSignal.riskControls(), base.riskControls())
        );
    }

    private boolean tailEvidenceAvailableAtDecision(
            ShortTermTailSignal tailSignal,
            LocalDateTime decisionAt
    ) {
        if (tailSignal == null
                || !tailSignal.actionableTailWindow()
                || decisionAt == null
                || tailSignal.tradeDate() == null
                || tailSignal.latestMinute() == null) {
            return false;
        }
        try {
            LocalDate evidenceDate = LocalDate.parse(tailSignal.tradeDate());
            LocalTime evidenceTime = LocalTime.parse(tailSignal.latestMinute());
            if (!evidenceDate.equals(decisionAt.toLocalDate())) {
                return false;
            }
            return !evidenceTime.isBefore(ACTIONABLE_TAIL_START)
                    && evidenceTime.isBefore(ACTIONABLE_TAIL_END_EXCLUSIVE)
                    && !LocalDateTime.of(evidenceDate, evidenceTime).isAfter(decisionAt);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private boolean shouldLightTrial(
            TradingAdvice base,
            String candidateAction,
            ShortTermTailSignal tailSignal,
            boolean recentConfirmedGoldenCross
    ) {
        boolean supportReversal = "SUPPORT_REVERSAL_LIGHT_TRIAL".equals(candidateAction);
        return (supportReversal || recentConfirmedGoldenCross)
                && (supportReversal || isRightSideExecutableCandidate(candidateAction) || "LIGHT_TRIAL".equals(base.action()))
                && ("CONFIRMED".equals(tailSignal.status()) || "WATCH".equals(tailSignal.status()));
    }

    private boolean shouldNextDayWatch(TradingAdvice base, String candidateAction, ShortTermTailSignal tailSignal) {
        return isRightSideExecutableCandidate(candidateAction)
                && ("WEAK".equals(tailSignal.status()) || "POST_CLOSE_FIXED_PRICE".equals(tailSignal.status()));
    }

    private boolean isRightSideExecutableCandidate(String candidateAction) {
        return "RIGHT_EARLY_ADD".equals(candidateAction);
    }

    private boolean isPullbackAdvice(TradingAdvice base) {
        return "WAIT_PULLBACK".equals(base.action());
    }

    private int lightTrialConfidence(TradingAdvice base, ShortTermTailSignal tailSignal) {
        int tailScore = tailSignal.score() == null ? 60 : tailSignal.score().setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(58, Math.min(78, Math.max(base.confidence(), tailScore)));
    }

    private String lightTrialSummary(TradingAdvice base) {
        if (base != null && base.summary() != null
                && (base.summary().contains("高潮")
                || base.summary().contains("修复")
                || base.summary().contains("拥挤高波动"))) {
            return base.summary();
        }
        return "右侧结构已经进入可试错区，但还没有达到强加仓标准，只允许轻仓试错，不追第二笔。";
    }

    private int nextWatchConfidence(TradingAdvice base) {
        return Math.max(50, Math.min(72, base.confidence()));
    }

    private ShortTermTailSignal pendingTailSignal() {
        return new ShortTermTailSignal(
                "PENDING",
                "待拉取",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("40"),
                List.of("14:45-14:49 可执行尾盘信号会在候选入围后拉取。"),
                List.of("未完成可执行尾盘确认前不执行短线买入。")
        );
    }

    private List<String> withTailEntryRule(List<String> rules) {
        List<String> merged = new ArrayList<>(rules);
        merged.add("最近三根已完成交易日内确认金叉后，14:45-14:49 仍必须复核当时已产生的尾盘分时；尾盘只能确认买点，不能为旧金叉或未确认金叉创造买点。");
        merged.add("14:57 后数据只用于历史复盘，15:05-15:30 盘后固定价格不能替代普通尾盘确认。");
        return merged.stream().distinct().toList();
    }

    private List<ShortTermEvidence> withTailEvidence(List<ShortTermEvidence> evidence, ShortTermTailSignal tailSignal) {
        List<ShortTermEvidence> merged = new ArrayList<>(evidence);
        String minuteText = tailSignal.latestMinute() == null ? "待补充" : tailSignal.tradeDate() + " " + tailSignal.latestMinute();
        merged.add(new ShortTermEvidence(
                "尾盘分时",
                tailSignal.statusLabel() + "：最新分时 " + minuteText
                        + "，14:45-14:49 涨跌 " + valueText(tailSignal.changeFromActionableTailPercent())
                        + "%，高点回落 " + valueText(tailSignal.drawdownFromTailHighPercent())
                        + "%，相对均价线 " + valueText(tailSignal.closeVsAveragePricePercent())
                        + "%，尾盘成交占比 " + valueText(tailSignal.tailAmountRatioPercent()) + "%。",
                null,
                35
        ));
        return merged;
    }

    private EvidenceCompleteness pendingEvidenceCompleteness(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            QuoteFreshnessSnapshot quoteFreshness
    ) {
        return evidenceCompletenessService.evaluate(EvidenceCompletenessInput.shortTerm(
                isFreshRealtimeQuote(quoteFreshness) && quote.latestPrice() != null && quote.amount() != null,
                true,
                technical.tradeDate() != null,
                financial != null && financial.qualityScore() != null && financial.dataGaps().isEmpty(),
                false,
                false,
                false,
                merge(
                        financial == null ? List.of("财报数据缺失") : financial.dataGaps(),
                        isFreshRealtimeQuote(quoteFreshness) ? List.of() : List.of(quoteFreshness.reason())
                )
        ));
    }

    private EvidenceCompleteness evidenceCompleteness(ShortTermCandidate candidate, ShortTermTailSignal tailSignal) {
        return evidenceCompletenessService.evaluate(EvidenceCompletenessInput.shortTerm(
                isFreshRealtimeQuote(candidate.quoteFreshness())
                        && candidate.latestPrice() != null
                        && candidate.amount() != null,
                true,
                candidate.technical().tradeDate() != null,
                candidate.financial() != null
                        && candidate.financial().qualityScore() != null
                        && candidate.financial().dataGaps().isEmpty(),
                "CONFIRMED".equals(tailSignal.status()),
                false,
                false,
                merge(
                        candidate.financial() == null ? List.of("财报数据缺失") : candidate.financial().dataGaps(),
                        isFreshRealtimeQuote(candidate.quoteFreshness())
                                ? List.of()
                                : List.of(candidate.quoteFreshness().reason())
                )
        ));
    }

    private boolean isFreshRealtimeQuote(QuoteFreshnessSnapshot quoteFreshness) {
        return quoteFreshness != null && "FRESH".equals(quoteFreshness.status());
    }

    private boolean isGoldenCrossWatchLayer(ShortTermTechnicalSnapshot technical) {
        return technical != null && technical.goldenCross() != null && technical.goldenCross().watchLayer();
    }

    private boolean hasRecentConfirmedGoldenCross(ShortTermTechnicalSnapshot technical) {
        return technical != null && technical.goldenCross() != null && technical.goldenCross().confirmedRecent();
    }

    private boolean isPostCloseFixedPriceMinute(LocalTime time) {
        return !time.isBefore(POST_CLOSE_FIXED_PRICE_START) && !time.isAfter(POST_CLOSE_FIXED_PRICE_END);
    }

    private List<String> merge(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(6)
                .toList();
    }

    private boolean gte(BigDecimal value, String threshold) {
        return value != null && value.compareTo(new BigDecimal(threshold)) >= 0;
    }

    private boolean lte(BigDecimal value, String threshold) {
        return value != null && value.compareTo(new BigDecimal(threshold)) <= 0;
    }

    private BigDecimal supportReversalScore(ShortTermTechnicalSnapshot technical) {
        if (technical == null || technical.supportReversal() == null || technical.supportReversal().score() == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        ShortTermSupportReversalSignal signal = technical.supportReversal();
        if (!signal.confirmed() && !signal.watchLayer()) {
            return BigDecimal.ZERO.setScale(2);
        }
        return signal.score();
    }

    private String supportReversalEvidence(ShortTermSupportReversalSignal signal) {
        if (signal == null) {
            return "微跌候选缺少长下影承接认证结果，不进入推荐池。";
        }
        List<String> details = new ArrayList<>();
        details.add("下影线占比 " + valueText(signal.lowerShadowPercent()) + "%");
        details.add("收盘位置 " + valueText(signal.closeLocationPercent()) + "%");
        details.add("认证分 " + valueText(signal.score()));
        details.addAll(signal.reasons());
        details.addAll(signal.dataGaps());
        return String.join("；", details) + "。";
    }

    private String supportName(ShortTermSupportReversalSignal signal) {
        if (signal == null || signal.supportType() == null || signal.supportPrice() == null) {
            return "关键支撑";
        }
        String label = switch (signal.supportType()) {
            case "MA5" -> "5 日线";
            case "MA10" -> "10 日线";
            case "MA20" -> "20 日线";
            case "PREVIOUS_HIGH20" -> "前 20 日高点";
            default -> signal.supportType();
        };
        return label + " " + money(signal.supportPrice()) + " 元";
    }

    private List<String> strengths(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial
    ) {
        List<String> strengths = new ArrayList<>();
        ShortTermSupportReversalSignal supportReversal = technical.supportReversal();
        if (supportReversal != null && supportReversal.confirmed()) {
            strengths.add("微跌长下影收复 " + supportName(supportReversal) + "，下方承接信号已通过独立认证。");
        }
        if (isQualifiedRightSideSignal(technical.rightSideSignal())) {
            strengths.add("K 线处于" + technical.rightSideSignal() + "，不是纯左侧猜底。");
        }
        if (technical.volumeRatio20() != null && technical.volumeRatio20().compareTo(new BigDecimal("1.10")) >= 0) {
            strengths.add("20 日量比约 " + technical.volumeRatio20() + "，有温和放量迹象。");
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(BigDecimal.ZERO) > 0
                && technical.volumeRatio20() != null
                && technical.volumeRatio20().compareTo(new BigDecimal("0.55")) >= 0
                && technical.volumeRatio20().compareTo(new BigDecimal("1.05")) <= 0
                && isQualifiedRightSideSignal(technical.rightSideSignal())) {
            strengths.add("上涨时 20 日量比约 " + technical.volumeRatio20() + "，属于缩量上涨，可能反映惜售和抛压减轻。");
        }
        if (technical.ma20SlopePercent() != null && technical.ma20SlopePercent().compareTo(new BigDecimal("-0.20")) >= 0) {
            strengths.add("20 日线斜率约 " + technical.ma20SlopePercent() + "%，中短均线开始走平或上拐。");
        }
        if (technical.breakoutFromPreviousHigh20Percent() != null
                && technical.breakoutFromPreviousHigh20Percent().compareTo(BigDecimal.ZERO) >= 0
                && technical.breakoutFromPreviousHigh20Percent().compareTo(new BigDecimal("6")) <= 0) {
            strengths.add("价格突破前 20 日高点约 " + technical.breakoutFromPreviousHigh20Percent() + "%，突破幅度尚未过度拉开。");
        }
        ShortTermMomentumQuality momentum = technical.momentumQuality();
        if (momentum != null && "PREFERRED".equals(momentum.turnoverBand())) {
            strengths.add("换手率 " + valueText(momentum.turnoverRatePercent()) + "% 位于 2%-5% 优选区间。");
        }
        if (momentum != null && momentum.closeStrengthScore() != null
                && momentum.closeStrengthScore().compareTo(new BigDecimal("75")) >= 0) {
            strengths.add(momentum.closeStrengthLabel() + "，最新收盘位置约 "
                    + valueText(momentum.closeLocationPercent()) + "%。");
        }
        if (financial != null && financial.qualityScore().compareTo(new BigDecimal("58")) >= 0) {
            strengths.add(financial.statusLabel() + "，财报没有明显否决右侧交易。");
        }
        if (strengths.isEmpty()) {
            strengths.add("暂未形成强优势，只能作为观察样本。");
        }
        return strengths;
    }

    private List<String> supplyDemandStrengths(
            List<String> base,
            ShortTermSupplyDemandScore supplyDemand
    ) {
        List<String> strengths = new ArrayList<>(base == null ? List.of() : base);
        if (supplyDemand.mainNetInflowRatio() != null
                && supplyDemand.mainNetInflowRatio().compareTo(BigDecimal.ZERO) > 0) {
            strengths.add("主力净流入占比 " + valueText(supplyDemand.mainNetInflowRatio())
                    + "%，买盘强度 " + valueText(supplyDemand.buyPressureScore()) + " 分。");
        }
        if (supplyDemand.overheadPressureReliefScore().compareTo(new BigDecimal("70")) >= 0) {
            strengths.add("上影、收盘位置和前 20 日压力位共同显示抛压较弱，缓解分 "
                    + valueText(supplyDemand.overheadPressureReliefScore()) + " 分。");
        }
        return strengths.stream().distinct().limit(8).toList();
    }

    private List<String> risks(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            ShortTermRuleSet ruleSet,
            QuoteFreshnessSnapshot quoteFreshness
    ) {
        List<String> risks = new ArrayList<>();
        if (quoteFreshness != null && quoteFreshness.blocksRealtimeDecision()) {
            risks.add(quoteFreshness.reason());
        }
        ShortTermGoldenCrossSnapshot goldenCross = technical.goldenCross();
        if (goldenCross == null || "UNAVAILABLE".equals(goldenCross.state())) {
            risks.add("均线金叉数据不足，不能把未复核的均线关系当作执行依据。");
        } else if ("FORMING".equals(goldenCross.state())) {
            risks.add("金叉仍在形成中，最新日线未完成前只能观察。");
        } else if ("APPROACHING".equals(goldenCross.state())) {
            risks.add("均线仍处于临界交汇，尚未形成确认金叉，不能执行买入。");
        } else if ("CONFIRMED".equals(goldenCross.state()) && isChaseRisk(quote, technical, ruleSet)) {
            risks.add("确认金叉后价格已明显拉开，短线只等回踩，不追高。");
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(ruleSet.maxEntryRisePercent()) > 0) {
            risks.add("单日涨幅超过入场上限，短线追高风险上升。");
        }
        if (technical.distanceToMa20Percent() != null
                && technical.distanceToMa20Percent().compareTo(ruleSet.maxDistanceToMa20Percent()) > 0) {
            risks.add("距离 20 日线过远，回踩压力大。");
        }
        if (technical.volumeRatio20() != null && technical.volumeRatio20().compareTo(new BigDecimal("4.20")) > 0) {
            risks.add("量能过度放大，可能是情绪一致后的短线冲高。");
        }
        ShortTermMomentumQuality momentum = technical.momentumQuality();
        if (momentum != null && momentum.extremeUpperShadow()) {
            risks.add("最新上涨 K 线出现长上影且收盘位置偏低，只能观察，不能据此追入。");
        }
        if (momentum != null && Set.of("INSUFFICIENT", "OVERHEATED").contains(momentum.turnoverBand())) {
            risks.add("换手率 " + valueText(momentum.turnoverRatePercent())
                    + "% 不在 1%-8% 可保留区间，只作观察。");
        } else if (momentum != null && "OBSERVATION".equals(momentum.turnoverBand())) {
            risks.add("换手率 " + valueText(momentum.turnoverRatePercent())
                    + "% 位于降权带，尚未达到 2%-5% 优选区间。");
        }
        if (technical.ma20SlopePercent() != null && technical.ma20SlopePercent().compareTo(new BigDecimal("-0.80")) < 0) {
            risks.add("20 日线仍在明显下行，右侧确认不足，不宜急于抄底。");
        }
        ShortTermSupportReversalSignal supportReversal = technical.supportReversal();
        if (supportReversal != null && supportReversal.confirmed()) {
            risks.add("长下影只代表当日低位承接，不能单独证明主力做多；重新跌破承接低点或支撑位即失效。");
        }
        if (technical.breakoutFromPreviousHigh20Percent() != null
                && technical.breakoutFromPreviousHigh20Percent().compareTo(new BigDecimal("8")) > 0) {
            risks.add("突破前 20 日高点幅度偏大，短线追涨回撤风险上升。");
        }
        if (financial != null && financial.qualityScore().compareTo(ruleSet.minFinancialScore()) < 0) {
            risks.add("财报质量分低于阈值，价值回归逻辑需要复核。");
        }
        if (financial != null && !financial.dataGaps().isEmpty()) {
            risks.addAll(financial.dataGaps());
        }
        if (risks.isEmpty()) {
            risks.add("短线交易仍受指数波动和板块轮动影响，需要严格止损。");
        }
        return risks;
    }

    private List<String> supplyDemandRisks(
            List<String> base,
            ShortTermSupplyDemandScore supplyDemand
    ) {
        List<String> risks = new ArrayList<>(base == null ? List.of() : base);
        if (supplyDemand.mainNetInflowRatio() != null
                && supplyDemand.mainNetInflowRatio().compareTo(BigDecimal.ZERO) < 0) {
            risks.add("主力净流入占比 " + valueText(supplyDemand.mainNetInflowRatio())
                    + "% 为负，主动买盘不足，推荐顺位下调。");
        }
        if (supplyDemand.overheadPressureReliefScore().compareTo(new BigDecimal("55")) < 0) {
            risks.add("上影、收盘位置或前 20 日压力位显示上方抛压偏强，推荐顺位下调。");
        }
        risks.addAll(supplyDemand.dataGaps());
        return risks.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(10)
                .toList();
    }

    private List<String> entryRules(ActionDecision decision, ShortTermRuleSet ruleSet) {
        if ("RIGHT_EARLY_ADD".equals(decision.action())) {
            return List.of(
                    "第一笔只在收盘站稳 20 日线且上涨量比处于 "
                            + fixedTwo(ruleSet.minVolumeRatio()) + "-3.20 时执行。",
                    "换手率以 2%-5% 为优选区间，且上涨 K 线不能出现长上影弱收盘。",
                    "第二笔必须等待回踩不破 5/10/20 日线，不能在单日急拉后追。",
                    "财务持续亏损恶化或出现重大审计监管红旗时取消交易。"
            );
        }
        if ("SUPPORT_REVERSAL_LIGHT_TRIAL".equals(decision.action())) {
            return List.of(
                    "仅在微跌不超过 2%、下影线占比至少 50% 且收盘位置至少 70% 时保留。",
                    "必须触及并收复 MA5/MA10/MA20 或前 20 日高点，20 日量比保持 1.00-2.50。",
                    "尾盘证据通过后最多使用计划短线仓位的 1/5，不能追加为加仓动作。",
                    "重新失守收复支撑、跌破承接低点或次日弱开不能收回时退出。"
            );
        }
        return List.of(
                "右侧信号未共振时不主动建仓。",
                "等待确认金叉、上涨量比 " + fixedTwo(ruleSet.minVolumeRatio())
                        + "-3.20、换手率 2%-5% 和收盘强度共同改善。",
                "财报或 K 线关键证据缺失时只放观察池。"
        );
    }

    private List<String> exitRules(ShortTermRuleSet ruleSet) {
        return List.of(
                "跌破 20 日线且次日不能收回，退出短线试错。",
                "放量长阴跌破启动阳线低点，退出。",
                "单日涨幅过快后不加仓，等回踩确认。",
                "财报或公告出现经营恶化证据，取消价值回归逻辑。"
        );
    }

    private List<ShortTermEvidence> evidence(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            TechnicalContext context,
            QuoteFreshnessSnapshot quoteFreshness,
            ShortTermRuleSet ruleSet
    ) {
        List<ShortTermEvidence> evidence = new ArrayList<>();
        evidence.add(new ShortTermEvidence(
                "行情时点",
                quoteFreshness.statusLabel() + "：" + quoteFreshness.reason()
                        + " 交易日期=" + (quoteFreshness.tradeDate() == null ? "待补" : quoteFreshness.tradeDate())
                        + "，市场时间=" + (quoteFreshness.marketTimestamp() == null ? "待补" : quoteFreshness.marketTimestamp()) + "。",
                quote.quoteUrl(),
                20
        ));
        evidence.add(new ShortTermEvidence(
                "K线结构",
                "信号为" + technical.rightSideSignal()
                        + "，距 20 日线 " + valueText(technical.distanceToMa20Percent())
                        + "%，20 日线斜率 " + valueText(technical.ma20SlopePercent())
                        + "%，突破前 20 日高点 " + valueText(technical.breakoutFromPreviousHigh20Percent())
                        + "%。",
                quote.quoteUrl(),
                0
        ));
        ShortTermGoldenCrossSnapshot goldenCross = technical.goldenCross() == null
                ? ShortTermGoldenCrossSnapshot.unavailable()
                : technical.goldenCross();
        evidence.add(new ShortTermEvidence(
                "均线金叉",
                "状态 " + goldenCross.state() + "，交叉日期 " + (goldenCross.crossDate() == null ? "待复核" : goldenCross.crossDate())
                        + "，距交叉 " + (goldenCross.tradingDaysSinceCross() == null ? "待复核" : goldenCross.tradingDaysSinceCross()) + " 个交易日，"
                        + "MA5-MA10 有符号差值 " + valueText(goldenCross.ma5Ma10SpreadPercent()) + "%"
                        + "，差值趋势 " + goldenCross.spreadTrend() + "，均线排列 " + goldenCross.maAlignment()
                        + "，优先层级 " + goldenCross.priorityTier() + "，规则版本 " + goldenCross.ruleVersion() + "。",
                quote.quoteUrl(),
                45
        ));
        evidence.add(new ShortTermEvidence(
                "放量上涨",
                "当日涨跌幅 " + valueText(quote.changePercent()) + "%，20 日量比 "
                        + valueText(technical.volumeRatio20())
                        + "；上涨且量比 " + fixedTwo(ruleSet.minVolumeRatio())
                        + "-3.20 才属于主确认区间。",
                quote.quoteUrl(),
                30
        ));
        ShortTermMomentumQuality momentum = technical.momentumQuality() == null
                ? ShortTermMomentumQuality.unavailable()
                : technical.momentumQuality();
        evidence.add(new ShortTermEvidence(
                "换手适配",
                "换手率 " + valueText(momentum.turnoverRatePercent()) + "%，区间 "
                        + momentum.turnoverBand() + "，适配分 " + valueText(momentum.turnoverScore())
                        + "；2%-5% 优选，1%-8% 可观察。",
                quote.quoteUrl(),
                15
        ));
        evidence.add(new ShortTermEvidence(
                "K线收盘强度",
                "最新上影线占比 " + valueText(momentum.latestUpperShadowPercent())
                        + "%，最近三日上涨 K 线上影线中位数 "
                        + valueText(momentum.bullishUpperShadowMedian3Percent())
                        + "%，收盘位置 " + valueText(momentum.closeLocationPercent())
                        + "%，结论 " + momentum.closeStrengthLabel()
                        + (momentum.provisional() ? "（盘中暂定）" : "（正式日 K）") + "。",
                quote.quoteUrl(),
                10
        ));
        ShortTermSupportReversalSignal supportReversal = technical.supportReversal();
        if (supportReversal != null && !"UNAVAILABLE".equals(supportReversal.state())) {
            evidence.add(new ShortTermEvidence(
                    "长下影承接",
                    supportReversal.stateLabel()
                            + "：下影线占比 " + valueText(supportReversal.lowerShadowPercent())
                            + "% ，实体占比 " + valueText(supportReversal.bodyPercent())
                            + "% ，上影线占比 " + valueText(supportReversal.upperShadowPercent())
                            + "% ，收盘位置 " + valueText(supportReversal.closeLocationPercent())
                            + "% ；收复 " + supportName(supportReversal)
                            + "，独立认证分 " + valueText(supportReversal.score())
                            + "。该形态不单独推断主力做多。",
                    quote.quoteUrl(),
                    30
            ));
        }
        if (financial == null || financial.reportDate() == null) {
            evidence.add(new ShortTermEvidence("财报质量", "最近年报指标暂不可用，不能排除基本面红旗。", null, 0));
        } else {
            evidence.add(new ShortTermEvidence(
                    "财报质量",
                    financial.reportDate() + " 年报：ROE " + ratioPercentText(financial.roe()) + "，经营现金流/股 " + valueText(financial.operatingCashFlowPerShare()) + "，质量分 " + financial.qualityScore() + "。",
                    null,
                    0
            ));
        }
        if (!context.dataGaps().isEmpty()) {
            evidence.add(new ShortTermEvidence("数据缺口", String.join("；", context.dataGaps()), null, 10));
        }
        return evidence;
    }

    private List<ShortTermEvidence> supplyDemandEvidence(
            List<ShortTermEvidence> base,
            EastMoneyFundFlowSnapshot fundFlow,
            ShortTermSupplyDemandScore supplyDemand
    ) {
        List<ShortTermEvidence> evidence = new ArrayList<>(base == null ? List.of() : base);
        evidence.add(new ShortTermEvidence(
                "主力买盘",
                "主力净流入占比 " + valueText(supplyDemand.mainNetInflowRatio())
                        + "%，超大单与大单合计净流入占比 "
                        + valueText(supplyDemand.largeOrderNetInflowRatio())
                        + "%，买盘强度 " + valueText(supplyDemand.buyPressureScore())
                        + " 分；资金流只作为供需证据，不改写金叉、量能、换手与收盘强度主分。",
                fundFlow == null ? null : fundFlow.sourceUrl(),
                10
        ));
        evidence.add(new ShortTermEvidence(
                "上方抛压",
                "结合近期上涨 K 线上影、最新收盘位置与前 20 日压力位，抛压弱度 "
                        + valueText(supplyDemand.overheadPressureReliefScore())
                        + " 分；该项只解释近期价格行为中的上方供给压力。",
                null,
                20
        ));
        if (!supplyDemand.dataGaps().isEmpty()) {
            evidence.add(new ShortTermEvidence(
                    "供需数据缺口",
                    String.join("；", supplyDemand.dataGaps()),
                    null,
                    25
            ));
        }
        return evidence;
    }

    private String reason(EastMoneyQuote quote, ShortTermTechnicalSnapshot technical, ShortTermFinancialSnapshot financial, ActionDecision decision) {
        String financialText = financial == null ? "财报待复核" : financial.statusLabel();
        if ("SUPPORT_REVERSAL_LIGHT_TRIAL".equals(decision.action())) {
            ShortTermSupportReversalSignal support = technical.supportReversal();
            return decision.actionLabel()
                    + "：当日涨跌幅 " + valueText(quote.changePercent())
                    + "% ，长下影占比 " + valueText(support == null ? null : support.lowerShadowPercent())
                    + "% ，收盘位置 " + valueText(support == null ? null : support.closeLocationPercent())
                    + "% ，已收复 " + supportName(support)
                    + "；" + financialText + "。";
        }
        return decision.actionLabel()
                + "："
                + technical.rightSideSignal()
                + "，20 日量比 "
                + valueText(technical.volumeRatio20())
                + "，换手率 "
                + valueText(technical.momentumQuality() == null
                ? null
                : technical.momentumQuality().turnoverRatePercent())
                + "%，"
                + (technical.momentumQuality() == null
                ? "收盘强度待补"
                : technical.momentumQuality().closeStrengthLabel())
                + "，"
                + financialText
                + "。";
    }

    private String phase(ShortTermTechnicalSnapshot technical, ActionDecision decision) {
        if ("SUPPORT_REVERSAL_LIGHT_TRIAL".equals(decision.action())) {
            return "SUPPORT_REVERSAL";
        }
        if ("WAIT_PULLBACK".equals(decision.action())) {
            return "RIGHT_EXTENDED";
        }
        if (technical.rightSideSignal() != null && technical.rightSideSignal().contains("早期")) {
            return "RIGHT_EARLY";
        }
        if (technical.rightSideSignal() != null && technical.rightSideSignal().contains("雏形")) {
            return "BASE_TURNING";
        }
        return "WAITING";
    }

    private String phaseLabel(ShortTermTechnicalSnapshot technical, ActionDecision decision) {
        if ("SUPPORT_REVERSAL_LIGHT_TRIAL".equals(decision.action())) {
            return "长下影承接";
        }
        if ("WAIT_PULLBACK".equals(decision.action())) {
            return "右侧拉开";
        }
        if (technical.rightSideSignal() != null && technical.rightSideSignal().contains("早期")) {
            return "右侧早期";
        }
        if (technical.rightSideSignal() != null && technical.rightSideSignal().contains("雏形")) {
            return "底部转强";
        }
        return "等待右侧";
    }

    private boolean passesQuotePreFilter(
            EastMoneyQuote quote,
            ShortTermRuleSet ruleSet,
            Set<String> unstableIndustrySymbols
    ) {
        return preFilterExclusion(quote, ruleSet, unstableIndustrySymbols) == null;
    }

    private ShortTermRiskExclusion preFilterExclusion(
            EastMoneyQuote quote,
            ShortTermRuleSet ruleSet,
            Set<String> unstableIndustrySymbols
    ) {
        if (quote.latestPrice() != null
                && quote.latestPrice().compareTo(ruleSet.maxPricePerShare()) > 0) {
            return riskExclusion(
                    quote,
                    "PRICE_ABOVE_LIMIT",
                    "股价高于每股价格上限",
                    "最新价 " + valueText(quote.latestPrice()) + " 元高于每股价格上限 "
                            + ruleSet.maxPricePerShare().toPlainString()
                            + " 元，资金门槛外不进入量化筛选。"
            );
        }
        if (isShortTermUnstableIndustry(quote, unstableIndustrySymbols)) {
            return riskExclusion(
                    quote,
                    "UNSTABLE_INDUSTRY",
                    "证券/券商高 Beta",
                    "证券/券商股短线高度受市场成交额、自营投资收益、融资融券和监管节奏影响，默认剔除，避免把市场 Beta 误判为个股右侧启动。"
            );
        }
        if (!RecommendationQuality.hasSufficientLiquidity(quote, ruleSet.minAmount())) {
            return riskExclusion(
                    quote,
                    "LOW_LIQUIDITY",
                    "流动性不足",
                    RecommendationQuality.liquidityRiskText()
            );
        }
        if (quote.changePercent() == null) {
            return riskExclusion(
                    quote,
                    "CHANGE_DATA_MISSING",
                    "当日涨跌幅缺失",
                    "缺少当日实时涨跌幅，无法确认当前是否处于上升状态，不进入短线推荐候选。"
            );
        }
        if (quote.changePercent().compareTo(new BigDecimal("-2.00")) < 0) {
            return riskExclusion(
                    quote,
                    "DAILY_DECLINE_TOO_LARGE",
                    "当日跌幅超过承接观察上限",
                    "当日涨跌幅 " + valueText(quote.changePercent())
                            + "% 低于 -2.00%，不进入长下影承接复核。"
            );
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(ruleSet.maxEntryRisePercent()) > 0) {
            return riskExclusion(
                    quote,
                    "CHASE_RISK",
                    "超过追涨上限 " + valueText(ruleSet.maxEntryRisePercent()) + "%",
                    "当日涨幅 " + valueText(quote.changePercent()) + "% 超过追涨上限 "
                            + valueText(ruleSet.maxEntryRisePercent()) + "%，不进入短线推荐候选。"
            );
        }
        return null;
    }

    private ShortTermRiskExclusion technicalHardExclusion(TechnicalCandidate candidate) {
        EastMoneyQuote quote = candidate.quote();
        ShortTermTechnicalSnapshot technical = candidate.technical().snapshot();
        if (technical.tradeDate() == null) {
            return riskExclusion(
                    quote,
                    "KLINE_DATA_MISSING",
                    "K线数据缺失",
                    "未取得可用日 K，无法计算右侧结构、量能和金叉，不进入短线候选。"
            );
        }
        if (technical.volumeRatio20() == null) {
            return riskExclusion(
                    quote,
                    "VOLUME_DATA_MISSING",
                    "成交量数据缺失",
                    "无法计算当日成交量相对 20 日均量的比例，不使用默认分补足短线候选。"
            );
        }
        boolean nonPositiveChange = quote.changePercent() != null
                && quote.changePercent().compareTo(BigDecimal.ZERO) <= 0;
        ShortTermSupportReversalSignal supportReversal = technical.supportReversal();
        if (nonPositiveChange && (supportReversal == null || !supportReversal.confirmed())) {
            return riskExclusion(
                    quote,
                    "SUPPORT_REVERSAL_NOT_CONFIRMED",
                    "微跌承接未确认",
                    supportReversalEvidence(supportReversal)
            );
        }
        if (nonPositiveChange) {
            return null;
        }
        ShortTermGoldenCrossSnapshot goldenCross = technical.goldenCross();
        if (goldenCross == null
                || goldenCross.state() == null
                || Set.of("NONE", "UNAVAILABLE").contains(goldenCross.state())) {
            return riskExclusion(
                    quote,
                    "GOLDEN_CROSS_UNAVAILABLE",
                    "无有效金叉信号",
                    "MA5/MA10 未形成确认、形成中、临界或多头延续状态，不进入金叉优先短线候选。"
            );
        }
        return null;
    }

    private ShortTermRiskExclusion riskExclusion(EastMoneyQuote quote, String category, String reason, String evidence) {
        return new ShortTermRiskExclusion(
                quote.symbol(),
                quote.name(),
                quote.market(),
                quote.industry(),
                quote.latestPrice(),
                quote.changePercent(),
                quote.amount(),
                pe(quote),
                quote.pbRatio(),
                category,
                reason,
                evidence,
                quote.quoteUrl()
        );
    }

    private boolean isLongSidewaysWithoutBreakout(TechnicalCandidate candidate, ShortTermRuleSet ruleSet) {
        ShortTermTechnicalSnapshot snapshot = candidate.technical().snapshot();
        boolean rightConfirmed = "右侧早期确认".equals(snapshot.rightSideSignal())
                && hasVolumeConfirmation(snapshot.volumeRatio20(), ruleSet);
        boolean breakout = snapshot.breakoutFromPreviousHigh20Percent() != null
                && snapshot.breakoutFromPreviousHigh20Percent().compareTo(BigDecimal.ZERO) >= 0;
        return RecommendationQuality.isLongSideways(candidate.technical().rows())
                && !rightConfirmed
                && !breakout;
    }

    private Set<String> fetchUnstableIndustrySymbols() {
        try {
            return eastMoneyClient.fetchIndustryBoardConstituents("证券", 500).stream()
                    .map(EastMoneyQuote::symbol)
                    .filter(symbol -> symbol != null && !symbol.isBlank())
                    .collect(Collectors.toSet());
        } catch (RuntimeException exception) {
            logger.warn("证券板块动态成分获取失败，本次仅按行情行业字段识别", exception);
            return Set.of();
        }
    }

    private boolean isShortTermUnstableIndustry(EastMoneyQuote quote, Set<String> unstableIndustrySymbols) {
        String industry = quote.industry() == null ? "" : quote.industry();
        String name = quote.name() == null ? "" : quote.name();
        return unstableIndustrySymbols.contains(quote.symbol())
                || industry.contains("证券")
                || industry.contains("券商")
                || industry.contains("资本市场服务")
                || name.contains("证券")
                || name.contains("券商");
    }

    private BigDecimal preliminaryScore(EastMoneyQuote quote, ShortTermRuleSet ruleSet, Map<String, ShortTermHotDirection> hotDirectionMap) {
        BigDecimal liquidity = liquidityScore(quote, ruleSet);
        BigDecimal marketHeat = marketHeatScore(quote, hotDirectionMap);
        BigDecimal nonChase = quote.changePercent() == null
                ? new BigDecimal("60")
                : quote.changePercent().compareTo(ruleSet.maxEntryRisePercent()) <= 0 ? new BigDecimal("82") : new BigDecimal("42");
        return liquidity.multiply(WEIGHT_PROFILE.preliminaryLiquidity())
                .add(nonChase.multiply(WEIGHT_PROFILE.preliminaryNonChase()))
                .add(marketHeat.multiply(WEIGHT_PROFILE.preliminaryHeat()));
    }

    private List<ShortTermHotDirection> resolveHotDirections(List<EastMoneyQuote> quotes) {
        Map<String, HotDirectionStats> statsMap = new LinkedHashMap<>();
        for (EastMoneyQuote quote : quotes) {
            List<HotDirectionDefinition> matched = matchedHotDefinitions(quote);
            for (HotDirectionDefinition definition : matched) {
                statsMap.computeIfAbsent(definition.code(), ignored -> new HotDirectionStats(definition)).add(quote);
            }
        }
        return statsMap.values().stream()
                .filter(stats -> stats.sampleCount > 0)
                .map(HotDirectionStats::toDirection)
                .filter(this::hasIndependentHotDirectionEvidence)
                .sorted(Comparator.comparing(ShortTermHotDirection::heatScore).reversed()
                        .thenComparing(ShortTermHotDirection::totalAmount, Comparator.reverseOrder()))
                .limit(8)
                .toList();
    }

    private boolean hasIndependentHotDirectionEvidence(ShortTermHotDirection direction) {
        if (direction.code().startsWith("INDUSTRY:")) {
            return direction.sampleCount() >= 3;
        }
        return direction.sampleCount() >= 2
                || direction.totalAmount().compareTo(DEFAULT_MIN_AMOUNT.multiply(new BigDecimal("6"))) >= 0;
    }

    private BigDecimal marketHeatScore(EastMoneyQuote quote, Map<String, ShortTermHotDirection> hotDirectionMap) {
        List<HotDirectionDefinition> matched = matchedHotDefinitions(quote);
        if (matched.isEmpty()) {
            return new BigDecimal("60");
        }
        return matched.stream()
                .map(definition -> hotDirectionMap.get(definition.code()))
                .filter(direction -> direction != null)
                .map(ShortTermHotDirection::heatScore)
                .max(Comparator.naturalOrder())
                .orElse(new BigDecimal("60"));
    }

    private List<HotDirectionDefinition> matchedHotDefinitions(EastMoneyQuote quote) {
        String text = ((quote.name() == null ? "" : quote.name())
                + " "
                + (quote.industry() == null ? "" : quote.industry())).toUpperCase();
        List<HotDirectionDefinition> matched = new ArrayList<>();
        String industry = quote.industry() == null ? "" : quote.industry().trim();
        if (!industry.isBlank()) {
            matched.add(new HotDirectionDefinition("INDUSTRY:" + industry, industry, List.of()));
        }
        matched.addAll(HOT_DIRECTION_DEFINITIONS.stream()
                .filter(definition -> definition.keywords().stream()
                        .anyMatch(keyword -> text.contains(keyword.toUpperCase())))
                .toList());
        return matched;
    }

    private BigDecimal liquidityScore(EastMoneyQuote quote, ShortTermRuleSet ruleSet) {
        if (quote.amount() == null || quote.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("45");
        }
        BigDecimal ratio = quote.amount().divide(ruleSet.minAmount(), 6, RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("4")) >= 0) {
            return new BigDecimal("92");
        }
        if (ratio.compareTo(new BigDecimal("2")) >= 0) {
            return new BigDecimal("80");
        }
        if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            return new BigDecimal("68");
        }
        return new BigDecimal("52");
    }

    private List<EastMoneyKLine> fetchKLinesOrThrow(String symbol) {
        LocalDate end = tradingClockService.currentMarketDate();
        LocalDate begin = end.minusDays(420);
        List<EastMoneyKLine> rows = eastMoneyClient.fetchDailyKLines(symbol, begin, end);
        if (chipAnalysisService == null || !chipSettings.enabled()) {
            return rows;
        }
        List<EastMoneyKLine> enriched = eastMoneyClient.enrichDailyKLineTurnover(
                symbol, begin, end, rows);
        return enriched == null || enriched.isEmpty() ? rows : enriched;
    }

    private List<EastMoneyKLine> fetchKLinesSafely(String symbol) {
        try {
            return fetchKLinesOrThrow(symbol);
        } catch (RuntimeException exception) {
            logger.warn("短线右侧 K 线获取失败：{}", symbol, exception);
            return List.of();
        }
    }

    // K 线源健康守卫：第一只串行先抓，等价于源探测——失败（内部已多轮重试）立刻
    // 快速失败，避免逐只空等拖满扫描超时（例如东财历史行情接口限流封禁时）。
    private Map<String, List<EastMoneyKLine>> fetchKLinesWithSourceGuard(List<EastMoneyQuote> tradableQuotes) {
        EastMoneyQuote probe = tradableQuotes.get(0);
        List<EastMoneyKLine> probeRows;
        try {
            probeRows = fetchKLinesOrThrow(probe.symbol());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "K线数据源当前不可用（" + probe.symbol() + " 探测失败，可能被行情接口限流），请稍后重试",
                    exception
            );
        }
        Map<String, List<EastMoneyKLine>> klineMap = new ConcurrentHashMap<>();
        klineMap.put(probe.symbol(), probeRows);
        tradableQuotes.stream()
                .filter(quote -> !quote.symbol().equals(probe.symbol()))
                .parallel()
                .forEach(quote -> klineMap.put(quote.symbol(), fetchKLinesSafely(quote.symbol())));
        return klineMap;
    }

    private Map<String, EastMoneyFundFlowSnapshot> fetchFundFlows(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        try {
            return eastMoneyClient.fetchFundFlowSnapshots(symbols);
        } catch (RuntimeException exception) {
            logger.warn("短线候选批量资金流获取失败，本轮按资金流待补处理：{}", rootMessage(exception));
            return Map.of();
        }
    }

    private Map<String, ShortTermChipSnapshot> fetchChipSnapshots(
            List<TechnicalCandidate> candidates,
            Map<String, List<EastMoneyKLine>> klineMap,
            boolean allowExternalFetch,
            Instant dataCutoffAt
    ) {
        if (chipAnalysisService == null || !chipSettings.enabled()
                || candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        return candidates.parallelStream()
                .map(candidate -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                        candidate.quote().symbol(),
                        chipAnalysisService.analyze(
                                candidate.quote(),
                                klineMap.getOrDefault(candidate.quote().symbol(), List.of()),
                                allowExternalFetch,
                                dataCutoffAt
                        )
                ))
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left
                ));
    }

    private boolean allowExternalChipFetch(LocalDateTime decisionAt) {
        if (decisionAt == null) {
            return false;
        }
        LocalTime time = decisionAt.toLocalTime();
        return time.isBefore(ACTIONABLE_TAIL_START) || !time.isBefore(ACTIONABLE_TAIL_END_EXCLUSIVE);
    }

    private ShortTermRuleSet resolveRuleSet(
            Integer scanLimit,
            Integer klineLimit,
            BigDecimal minAmount,
            BigDecimal maxPricePerShare,
            BigDecimal minVolumeRatio,
            BigDecimal maxEntryRise,
            BigDecimal maxDistanceToMa20,
            BigDecimal minFinancialScore,
            Boolean allowChiNext
    ) {
        return new ShortTermRuleSet(
                Math.max(50, Math.min(scanLimit == null ? DEFAULT_SCAN_LIMIT : scanLimit, MAX_SCAN_LIMIT)),
                Math.max(10, Math.min(klineLimit == null ? DEFAULT_KLINE_LIMIT : klineLimit, MAX_KLINE_LIMIT)),
                RecommendationQuality.requiredAmount(positiveOrDefault(minAmount, DEFAULT_MIN_AMOUNT)),
                positiveOrDefault(maxPricePerShare, DEFAULT_MAX_PRICE_PER_SHARE),
                approvedVolumeRatioThreshold(minVolumeRatio),
                positiveOrDefault(maxEntryRise, DEFAULT_MAX_ENTRY_RISE),
                positiveOrDefault(maxDistanceToMa20, DEFAULT_MAX_DISTANCE_TO_MA20),
                positiveOrDefault(minFinancialScore, DEFAULT_MIN_FINANCIAL_SCORE),
                Boolean.TRUE.equals(allowChiNext)
        );
    }

    private BigDecimal approvedVolumeRatioThreshold(BigDecimal requested) {
        return positiveOrDefault(requested, DEFAULT_MIN_VOLUME_RATIO)
                .max(BigDecimal.ONE)
                .min(new BigDecimal("3.20"));
    }

    private int resolveLimit(Integer limit) {
        return Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, 40));
    }

    private List<ScoredShortTerm> attachRankingDiagnostics(List<ScoredShortTerm> scored) {
        Map<String, Integer> v2Ranks = rankingPositions(scored, shortTermV2RankingComparator());
        Map<String, Integer> v3Ranks = rankingPositions(scored, shortTermV3RankingComparator());
        return scored.stream()
                .map(item -> {
                    String symbol = item.candidate().symbol();
                    Integer v2Rank = v2Ranks.get(symbol);
                    Integer v3Rank = v3Ranks.get(symbol);
                    Integer rankDelta = v2Rank == null || v3Rank == null ? null : v2Rank - v3Rank;
                    return new ScoredShortTerm(copyWithScore(
                            item.candidate(),
                            withRankingDiagnostics(item.candidate().score(), v2Rank, v3Rank, rankDelta)
                    ));
                })
                .toList();
    }

    private Map<String, Integer> rankingPositions(
            List<ScoredShortTerm> scored,
            Comparator<ScoredShortTerm> comparator
    ) {
        List<ScoredShortTerm> ordered = scored.stream().sorted(comparator).toList();
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            positions.put(ordered.get(index).candidate().symbol(), index + 1);
        }
        return positions;
    }

    private ShortTermScoreBreakdown withRankingDiagnostics(
            ShortTermScoreBreakdown score,
            Integer v2Rank,
            Integer v3Rank,
            Integer rankDelta
    ) {
        return new ShortTermScoreBreakdown(
                score.technicalScore(), score.goldenCrossScore(), score.volumeScore(),
                score.turnoverScore(), score.closeStrengthScore(), score.supportReversalScore(), score.marketHeatScore(),
                score.financialScore(), score.riskPenalty(),
                score.finalScore(), score.stageAdjustment(), score.mainNetInflowRatio(),
                score.largeOrderNetInflowRatio(), score.buyPressureScore(),
                score.fundFlowAdjustment(),
                score.overheadPressureReliefScore(), score.technicalRankingScore(),
                score.v2RankingScore(), score.chipContributionScore(), score.v3RankingScore(),
                v2Rank, v3Rank, rankDelta,
                score.relativeStrengthContribution(), score.industryLeadershipContribution(),
                score.marketHeatContribution(), score.crossSectionAdjustment(),
                score.rankingScore(), score.volatilityContribution(), score.visibleRankingAdjustment()
        );
    }

    private ShortTermCandidate copyWithScore(
            ShortTermCandidate candidate,
            ShortTermScoreBreakdown score
    ) {
        return new ShortTermCandidate(
                candidate.rank(), candidate.symbol(), candidate.name(), candidate.market(), candidate.industry(),
                candidate.latestPrice(), candidate.changePercent(), candidate.peTtm(), candidate.pbRatio(),
                candidate.amount(), candidate.quoteFreshness(),
                candidate.phase(), candidate.phaseLabel(), candidate.action(), candidate.actionLabel(),
                candidate.reason(), candidate.todayAdvice(), candidate.tailSignal(), score,
                candidate.technical(), candidate.financial(), candidate.buyZoneLow(), candidate.buyZoneHigh(),
                candidate.stopPrice(), candidate.strengths(), candidate.risks(), candidate.entryRules(),
                candidate.exitRules(), candidate.evidenceCompleteness(), candidate.evidence(),
                candidate.tradePlan(), candidate.chip(), candidate.relativeStrength(), candidate.industryLeadership(),
                candidate.volatilityQuality(), candidate.signalProfile()
        );
    }

    private ShortTermCandidate rerank(ShortTermCandidate candidate, int rank) {
        return new ShortTermCandidate(
                rank,
                candidate.symbol(),
                candidate.name(),
                candidate.market(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.changePercent(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.amount(),
                candidate.quoteFreshness(),
                candidate.phase(),
                candidate.phaseLabel(),
                candidate.action(),
                candidate.actionLabel(),
                candidate.reason(),
                candidate.todayAdvice(),
                candidate.tailSignal(),
                candidate.score(),
                candidate.technical(),
                candidate.financial(),
                candidate.buyZoneLow(),
                candidate.buyZoneHigh(),
                candidate.stopPrice(),
                candidate.strengths(),
                candidate.risks(),
                candidate.entryRules(),
                candidate.exitRules(),
                candidate.evidenceCompleteness(),
                candidate.evidence(),
                candidate.tradePlan(),
                candidate.chip(),
                candidate.relativeStrength(),
                candidate.industryLeadership(),
                candidate.volatilityQuality(),
                candidate.signalProfile()
        );
    }

    private int actionPriority(String action) {
        return switch (action) {
            case "RIGHT_EARLY_ADD" -> 5;
            case "REGIME_LIGHT_TRIAL", "SUPPORT_REVERSAL_LIGHT_TRIAL", "RIGHT_EARLY_LIGHT_TRIAL", "WATCH_RIGHT_SIDE", "WATCH_VALUE_RETURN" -> 4;
            case "WAIT_PULLBACK" -> 3;
            case "WAIT_CONFIRM" -> 2;
            default -> 1;
        };
    }

    private Comparator<ScoredShortTerm> shortTermRankingComparator() {
        return shortTermV2RankingComparator();
    }

    private Comparator<ScoredShortTerm> shortTermV3RankingComparator() {
        return Comparator
                .comparingInt((ScoredShortTerm item) -> eligibilityGatePriority(item.candidate().action())).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ScoredShortTerm item) -> actionPriority(item.candidate().action())).reversed())
                .thenComparing(item -> item.candidate().score().v3RankingScore(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingInt(
                        (ScoredShortTerm item) -> eligibleGoldenCrossPriority(item.candidate())).reversed())
                .thenComparing(Comparator.comparingInt(
                        (ScoredShortTerm item) -> rightSideMaturityPriority(item.candidate())).reversed());
    }

    private Comparator<ScoredShortTerm> shortTermV2RankingComparator() {
        return Comparator
                .comparingInt((ScoredShortTerm item) -> eligibilityGatePriority(item.candidate().action())).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ScoredShortTerm item) -> eligibleGoldenCrossPriority(item.candidate())).reversed())
                .thenComparing(Comparator.comparingInt(
                        (ScoredShortTerm item) -> actionPriority(item.candidate().action())).reversed())
                .thenComparing(Comparator.comparingInt(
                        (ScoredShortTerm item) -> rightSideMaturityPriority(item.candidate())).reversed())
                .thenComparing(item -> item.candidate().score().rankingScore(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.candidate().symbol(), Comparator.nullsLast(String::compareTo));
    }

    private int eligibilityGatePriority(String action) {
        return Set.of("DATA_REVIEW", "MARKET_RISK_WAIT", "VALUATION_REVIEW").contains(action) ? 0 : 1;
    }

    private int goldenCrossTechnicalPriority(TechnicalCandidate candidate) {
        return qualifiedGoldenCrossPriority(candidate.technical().snapshot());
    }

    private int eligibleGoldenCrossPriority(ShortTermCandidate candidate) {
        int tier = qualifiedGoldenCrossPriority(candidate.technical());
        if (tier == 3 && Set.of("RIGHT_EARLY_ADD", "WATCH_RIGHT_SIDE").contains(candidate.action())) return 3;
        if (tier == 2 && !Set.of("DATA_REVIEW", "MARKET_RISK_WAIT", "VALUATION_REVIEW").contains(candidate.action())) return 2;
        return Math.min(tier, 1);
    }

    private int qualifiedGoldenCrossPriority(ShortTermTechnicalSnapshot technical) {
        if (technical == null) {
            return 0;
        }
        int supportTier = technical.supportReversal() != null && technical.supportReversal().confirmed() ? 2 : 0;
        if (technical.goldenCross() == null) {
            return supportTier;
        }
        int tier = technical.goldenCross().priorityTier();
        if (tier == 2 && !isEarlyRightSideSignal(technical.rightSideSignal())) {
            return supportTier;
        }
        return Math.max(tier, supportTier);
    }

    private int rightSideMaturityPriority(ShortTermCandidate candidate) {
        if (candidate.technical() != null
                && candidate.technical().supportReversal() != null
                && candidate.technical().supportReversal().confirmed()) {
            return 5;
        }
        String signal = candidate.technical() == null ? null : candidate.technical().rightSideSignal();
        if ("右侧早期确认".equals(signal)) {
            return 6;
        }
        if ("右侧早期观察".equals(signal)) {
            return 5;
        }
        if ("RIGHT_EARLY".equals(candidate.phase())) {
            return 5;
        }
        if ("BASE_TURNING".equals(candidate.phase())) {
            return 4;
        }
        if ("RIGHT_EXTENDED".equals(candidate.phase())) {
            return 3;
        }
        if (isQualifiedRightSideSignal(signal)) {
            return 2;
        }
        return 1;
    }

    private boolean isQualifiedRightSideSignal(String signal) {
        return Set.of("右侧早期确认", "右侧早期观察", "右侧已拉开", "右侧雏形").contains(signal);
    }

    private boolean isEarlyRightSideSignal(String signal) {
        return Set.of("右侧早期确认", "右侧早期观察").contains(signal);
    }

    private List<EastMoneyQuote> uniqueMarketQuotes(List<EastMoneyQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return List.of();
        }
        Map<String, EastMoneyQuote> unique = new LinkedHashMap<>();
        for (EastMoneyQuote quote : quotes) {
            if (quote != null && quote.symbol() != null && !quote.symbol().isBlank()) {
                unique.putIfAbsent(quote.symbol(), quote);
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean quoteAvailableAtDecision(
            EastMoneyQuote quote,
            LocalDateTime decisionAt,
            boolean allowClosedMarketCachePreview
    ) {
        if (quote == null || quote.marketTimestamp() == null || decisionAt == null) {
            return false;
        }
        LocalDate marketDate = quote.marketTimestamp().atZone(SHANGHAI).toLocalDate();
        Instant decisionInstant = decisionAt.atZone(SHANGHAI).toInstant();
        if (allowClosedMarketCachePreview) {
            return !quote.marketTimestamp().isAfter(decisionInstant);
        }
        if (!marketDate.equals(decisionAt.toLocalDate())) {
            return false;
        }
        if (quote.tradeDate() != null && !quote.tradeDate().equals(marketDate)) {
            return false;
        }
        return !quote.marketTimestamp().isAfter(decisionInstant);
    }

    private boolean isTradableCommonShare(EastMoneyQuote quote, boolean allowChiNext) {
        if (!isAshareContextQuote(quote)) {
            return false;
        }
        String name = quote.name().toUpperCase();
        if (name.contains("ST") || name.contains("退")) {
            return false;
        }
        if (!allowChiNext && isChiNext(quote.symbol())) {
            return false;
        }
        return true;
    }

    private boolean isAshareContextQuote(EastMoneyQuote quote) {
        if (quote == null || quote.symbol() == null || quote.name() == null) {
            return false;
        }
        String name = quote.name().toUpperCase();
        if (name.contains("官网") || name.contains("网站")
                || name.contains("首页") || name.contains("登录")
                || name.contains("ERROR") || name.contains("HTML")) {
            return false;
        }
        return quote.symbol().startsWith("0")
                || quote.symbol().startsWith("3")
                || quote.symbol().startsWith("4")
                || quote.symbol().startsWith("6")
                || quote.symbol().startsWith("8")
                || quote.symbol().startsWith("92");
    }

    private boolean isChiNext(String symbol) {
        return symbol != null && (symbol.startsWith("300") || symbol.startsWith("301"));
    }

    private boolean hasUsablePrice(EastMoneyQuote quote) {
        return quote.latestPrice() != null
                && quote.latestPrice().compareTo(BigDecimal.ZERO) > 0
                && quote.latestPrice().compareTo(new BigDecimal("100000")) < 0;
    }

    private BigDecimal latestPrice(EastMoneyQuote quote, TechnicalContext context) {
        if (quote.latestPrice() != null && quote.latestPrice().compareTo(BigDecimal.ZERO) > 0) {
            return quote.latestPrice();
        }
        return context.last() == null ? null : context.last().close();
    }

    private BigDecimal latestClose(EastMoneyQuote quote, EastMoneyKLine last) {
        if (quote.latestPrice() != null && quote.latestPrice().compareTo(BigDecimal.ZERO) > 0) {
            return quote.latestPrice();
        }
        return last == null ? null : last.close();
    }

    private BigDecimal buyZoneLow(BigDecimal price, ShortTermTechnicalSnapshot technical) {
        if (technical.ma20() != null) {
            return money(technical.ma20().multiply(new BigDecimal("0.995")));
        }
        return price == null ? null : money(price.multiply(new BigDecimal("0.97")));
    }

    private BigDecimal buyZoneHigh(BigDecimal price, ShortTermTechnicalSnapshot technical, ShortTermRuleSet ruleSet) {
        if (technical.ma20() != null) {
            return money(technical.ma20().multiply(BigDecimal.ONE.add(ruleSet.maxDistanceToMa20Percent().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))));
        }
        return price == null ? null : money(price);
    }

    private BigDecimal stopPrice(BigDecimal price, ShortTermTechnicalSnapshot technical) {
        if (technical.ma20() != null) {
            return money(technical.ma20().multiply(new BigDecimal("0.96")));
        }
        return price == null ? null : money(price.multiply(new BigDecimal("0.94")));
    }

    private BigDecimal pe(EastMoneyQuote quote) {
        return quote.peTtm() != null ? quote.peTtm() : quote.peRatio();
    }

    private BigDecimal movingAverage(List<EastMoneyKLine> rows, int window) {
        List<EastMoneyKLine> slice = lastRows(rows, window);
        if (slice.size() < Math.min(window, rows.size())) {
            return null;
        }
        return average(slice.stream().map(EastMoneyKLine::close).toList());
    }

    private BigDecimal atr14Percent(List<EastMoneyKLine> completedRows) {
        if (completedRows == null || completedRows.size() < 15) {
            return null;
        }
        int start = Math.max(1, completedRows.size() - 14);
        BigDecimal totalTrueRange = BigDecimal.ZERO;
        int count = 0;
        for (int index = start; index < completedRows.size(); index++) {
            BigDecimal trueRange = trueRange(completedRows.get(index), completedRows.get(index - 1));
            if (trueRange != null) {
                totalTrueRange = totalTrueRange.add(trueRange);
                count++;
            }
        }
        EastMoneyKLine latest = completedRows.get(completedRows.size() - 1);
        if (count < 14 || latest.close() == null || latest.close().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal averageTrueRange = totalTrueRange.divide(
                BigDecimal.valueOf(count),
                6,
                RoundingMode.HALF_UP
        );
        return percent(averageTrueRange, latest.close());
    }

    private BigDecimal trueRange(EastMoneyKLine current, EastMoneyKLine previous) {
        if (current == null
                || previous == null
                || current.high() == null
                || current.low() == null
                || previous.close() == null) {
            return null;
        }
        BigDecimal intradayRange = current.high().subtract(current.low()).abs();
        BigDecimal highGap = current.high().subtract(previous.close()).abs();
        BigDecimal lowGap = current.low().subtract(previous.close()).abs();
        return intradayRange.max(highGap).max(lowGap);
    }

    private BigDecimal movingAverageSlope(List<EastMoneyKLine> rows, int window, int lookbackDays) {
        if (rows == null || rows.size() <= window + lookbackDays) {
            return null;
        }
        BigDecimal current = movingAverage(rows, window);
        BigDecimal previous = movingAverage(rows.subList(0, rows.size() - lookbackDays), window);
        if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return percent(current.subtract(previous), previous);
    }

    private boolean hasVolumeConfirmation(BigDecimal volumeRatio20, ShortTermRuleSet ruleSet) {
        return volumeRatio20 != null
                && volumeRatio20.compareTo(ruleSet.minVolumeRatio()) >= 0
                && volumeRatio20.compareTo(new BigDecimal("3.20")) <= 0;
    }

    private BigDecimal volumeRatio(List<EastMoneyKLine> rows, int window) {
        if (rows.size() <= window) {
            return null;
        }
        EastMoneyKLine last = rows.get(rows.size() - 1);
        if (last.volume() == null || last.volume().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        List<EastMoneyKLine> previousRows = rows.subList(0, rows.size() - 1);
        BigDecimal averageVolume = average(lastRows(previousRows, window).stream().map(EastMoneyKLine::volume).toList());
        if (averageVolume == null || averageVolume.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return last.volume().divide(averageVolume, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal high(List<EastMoneyKLine> rows, int window) {
        return lastRows(rows, window).stream()
                .map(EastMoneyKLine::high)
                .filter(value -> value != null)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private BigDecimal low(List<EastMoneyKLine> rows, int window) {
        return lastRows(rows, window).stream()
                .map(EastMoneyKLine::low)
                .filter(value -> value != null)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    private List<EastMoneyKLine> lastRows(List<EastMoneyKLine> rows, int window) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.subList(Math.max(0, rows.size() - window), rows.size());
    }

    private BigDecimal rangePosition(BigDecimal close, BigDecimal low, BigDecimal high) {
        if (close == null || low == null || high == null || high.compareTo(low) <= 0) {
            return null;
        }
        return close.subtract(low).divide(high.subtract(low), 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    private int consecutiveAboveMa(List<EastMoneyKLine> rows, int window) {
        int count = 0;
        for (int index = rows.size() - 1; index >= window - 1; index--) {
            List<EastMoneyKLine> slice = rows.subList(0, index + 1);
            BigDecimal ma = movingAverage(slice, window);
            EastMoneyKLine row = rows.get(index);
            if (ma == null || row.close() == null || row.close().compareTo(ma) <= 0) {
                break;
            }
            count++;
        }
        return count;
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> valid = values.stream()
                .filter(value -> value != null)
                .toList();
        if (valid.isEmpty()) {
            return null;
        }
        BigDecimal sum = valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(valid.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value.compareTo(new BigDecimal("100")) > 0) {
            return new BigDecimal("100.00");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal boundedContribution(BigDecimal value, BigDecimal absoluteLimit) {
        if (value == null || absoluteLimit == null || absoluteLimit.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.max(absoluteLimit.negate())
                .min(absoluteLimit)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyInYi(BigDecimal value) {
        if (value == null) {
            return "待复核";
        }
        return value.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + " 亿";
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int confidence(BigDecimal finalScore) {
        return Math.max(35, Math.min(90, finalScore == null ? 50 : finalScore.setScale(0, RoundingMode.HALF_UP).intValue()));
    }

    private String valueText(BigDecimal value) {
        return value == null ? "待复核" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String fixedTwo(BigDecimal value) {
        return value == null ? "待复核" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String ratioPercentText(BigDecimal value) {
        BigDecimal percentPoints = RatioScale.toPercentPoints(value);
        return percentPoints == null ? "待复核" : valueText(percentPoints) + "%";
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private record HotDirectionDefinition(
            String code,
            String label,
            List<String> keywords
    ) {
    }

    private final class HotDirectionStats {

        private final HotDirectionDefinition definition;
        private final List<EastMoneyQuote> quotes = new ArrayList<>();
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private BigDecimal weightedChange = BigDecimal.ZERO;
        private BigDecimal totalWeight = BigDecimal.ZERO;
        private int sampleCount;
        private int positiveCount;

        private HotDirectionStats(HotDirectionDefinition definition) {
            this.definition = definition;
        }

        private void add(EastMoneyQuote quote) {
            BigDecimal amount = quote.amount() == null || quote.amount().compareTo(BigDecimal.ZERO) <= 0
                    ? DEFAULT_MIN_AMOUNT
                    : quote.amount();
            BigDecimal change = quote.changePercent() == null ? BigDecimal.ZERO : quote.changePercent();
            sampleCount++;
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                positiveCount++;
            }
            totalAmount = totalAmount.add(amount);
            weightedChange = weightedChange.add(change.multiply(amount));
            totalWeight = totalWeight.add(amount);
            quotes.add(quote);
        }

        private ShortTermHotDirection toDirection() {
            BigDecimal averageChange = totalWeight.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : weightedChange.divide(totalWeight, 6, RoundingMode.HALF_UP);
            BigDecimal positiveRatio = sampleCount == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(positiveCount).multiply(new BigDecimal("100"))
                    .divide(BigDecimal.valueOf(sampleCount), 6, RoundingMode.HALF_UP);
            BigDecimal liquidityBoost = totalAmount.divide(DEFAULT_MIN_AMOUNT, 6, RoundingMode.HALF_UP)
                    .min(new BigDecimal("15"))
                    .multiply(new BigDecimal("0.50"));
            BigDecimal sampleBoost = BigDecimal.valueOf(Math.min(sampleCount, 20)).multiply(new BigDecimal("0.40"));
            BigDecimal boundedChange = averageChange.max(new BigDecimal("-5")).min(new BigDecimal("8"));
            BigDecimal heatScore = clamp(new BigDecimal("35")
                    .add(boundedChange.multiply(new BigDecimal("4")))
                    .add(positiveRatio.multiply(new BigDecimal("0.25")))
                    .add(liquidityBoost)
                    .add(sampleBoost));
            List<String> leaders = quotes.stream()
                    .sorted(Comparator.comparing((EastMoneyQuote quote) -> quote.changePercent() == null ? BigDecimal.ZERO : quote.changePercent()).reversed()
                            .thenComparing(quote -> quote.amount() == null ? BigDecimal.ZERO : quote.amount(), Comparator.reverseOrder()))
                    .limit(3)
                    .map(quote -> quote.name() + "(" + quote.symbol() + ")")
                    .toList();
            return new ShortTermHotDirection(
                    definition.code(),
                    definition.label(),
                    heatScore,
                    scale(averageChange),
                    scale(positiveRatio),
                    money(totalAmount),
                    sampleCount,
                    leaders,
                    "按通过前置风控后的实时行情聚合：成交额加权涨跌幅 " + valueText(averageChange)
                            + "%，上涨占比 " + valueText(positiveRatio)
                            + "%，样本 " + sampleCount + " 只。"
            );
        }
    }

    private static final class IndustryMomentumStats {

        private final String industry;
        private int sampleCount;
        private int advancingCount;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private BigDecimal changeSum = BigDecimal.ZERO;

        private IndustryMomentumStats(String industry) {
            this.industry = industry;
        }

        private void add(EastMoneyQuote quote) {
            sampleCount++;
            BigDecimal change = quote.changePercent() == null ? BigDecimal.ZERO : quote.changePercent();
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                advancingCount++;
            }
            changeSum = changeSum.add(change);
            if (quote.amount() != null && quote.amount().compareTo(BigDecimal.ZERO) > 0) {
                totalAmount = totalAmount.add(quote.amount());
            }
        }

        private StructuralHotCluster toCluster() {
            if (sampleCount == 0) {
                return StructuralHotCluster.unavailable();
            }
            BigDecimal averageChange = changeSum.divide(BigDecimal.valueOf(sampleCount), 4, RoundingMode.HALF_UP);
            BigDecimal advancingRatio = BigDecimal.valueOf(advancingCount)
                    .multiply(new BigDecimal("100"))
                    .divide(BigDecimal.valueOf(sampleCount), 4, RoundingMode.HALF_UP);
            boolean available = sampleCount >= 4
                    && advancingRatio.compareTo(new BigDecimal("75")) >= 0
                    && averageChange.compareTo(new BigDecimal("1.80")) >= 0
                    && totalAmount.compareTo(DEFAULT_MIN_AMOUNT.multiply(new BigDecimal("20"))) >= 0;
            BigDecimal heatScore = averageChange.multiply(new BigDecimal("12"))
                    .add(advancingRatio.multiply(new BigDecimal("0.45")))
                    .add(totalAmount.divide(DEFAULT_MIN_AMOUNT, 4, RoundingMode.HALF_UP).min(new BigDecimal("40")));
            return new StructuralHotCluster(
                    available,
                    industry,
                    sampleCount,
                    advancingCount,
                    averageChange.setScale(2, RoundingMode.HALF_UP),
                    totalAmount.setScale(2, RoundingMode.HALF_UP),
                    heatScore
            );
        }
    }

    private record StructuralHotCluster(
            boolean available,
            String industry,
            int sampleCount,
            int advancingCount,
            BigDecimal averageChangePercent,
            BigDecimal totalAmount,
            BigDecimal heatScore
    ) {
        private static StructuralHotCluster unavailable() {
            return new StructuralHotCluster(false, null, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private record TechnicalContext(
            EastMoneyQuote quote,
            List<EastMoneyKLine> rows,
            ShortTermTechnicalSnapshot snapshot,
            EastMoneyKLine last,
            EastMoneyKLine previous,
            List<String> dataGaps
    ) {
    }

    private record TechnicalCandidate(
            EastMoneyQuote quote,
            TechnicalContext technical,
            BigDecimal technicalScore,
            ShortTermCoreSignalScore coreSignalScore,
            ShortTermVolatilityQuality volatilityQuality
    ) {
    }

    private record ActionDecision(String action, String actionLabel) {
    }

    private record StageAdjustedScore(
            BigDecimal rawScore,
            BigDecimal adjustment,
            BigDecimal rankingScore
    ) {
    }

    private record ScoredShortTerm(ShortTermCandidate candidate) {
    }

}

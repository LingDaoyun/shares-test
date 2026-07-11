package com.aistock.research.shortterm;

import com.aistock.research.factor.RatioScale;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.EvidenceCompletenessInput;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.quality.RecommendationQuality;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.AsharePriceLimitRule;
import com.aistock.research.trading.QuoteFreshnessService;
import com.aistock.research.trading.QuoteFreshnessSnapshot;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.valuation.ValuationContext;
import com.aistock.research.valuation.ValuationContextCalculator;
import com.aistock.research.valuation.ValuationContextState;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class ShortTermService {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermService.class);
    private static final int DEFAULT_LIMIT = 8;
    private static final int DEFAULT_SCAN_LIMIT = 6000;
    private static final int MAX_SCAN_LIMIT = 6000;
    private static final int DEFAULT_KLINE_LIMIT = 60;
    private static final int MAX_KLINE_LIMIT = 160;
    private static final BigDecimal DEFAULT_MIN_AMOUNT = new BigDecimal("80000000");
    private static final BigDecimal DEFAULT_MAX_PE = new BigDecimal("100");
    private static final BigDecimal DEFAULT_MAX_PB = new BigDecimal("15.0");
    private static final BigDecimal DEFAULT_MIN_VOLUME_RATIO = new BigDecimal("1.15");
    private static final BigDecimal DEFAULT_MAX_ENTRY_RISE = new BigDecimal("4.00");
    private static final BigDecimal DEFAULT_MAX_DISTANCE_TO_MA20 = new BigDecimal("8.00");
    private static final BigDecimal DEFAULT_MIN_FINANCIAL_SCORE = new BigDecimal("58");
    private static final BigDecimal MIN_RELIABLE_MARKET_COVERAGE = new BigDecimal("0.90");
    private static final BigDecimal ROE_STRONG = RatioScale.fromPercentPoints("12");
    private static final BigDecimal ROE_ACCEPTABLE = RatioScale.fromPercentPoints("8");
    private static final BigDecimal GROSS_MARGIN_STRONG = RatioScale.fromPercentPoints("30");
    private static final BigDecimal GROSS_MARGIN_ACCEPTABLE = RatioScale.fromPercentPoints("15");
    private static final BigDecimal LARGE_TURNOVER_TAIL_AMOUNT = new BigDecimal("2000000000");
    private static final BigDecimal MEDIUM_TURNOVER_TAIL_AMOUNT = new BigDecimal("800000000");
    private static final LocalTime TAIL_CONFIRM_TIME = LocalTime.of(14, 57);
    private static final LocalTime REGULAR_CLOSE_TIME = LocalTime.of(15, 0);
    private static final LocalTime POST_CLOSE_FIXED_PRICE_START = LocalTime.of(15, 5);
    private static final LocalTime POST_CLOSE_FIXED_PRICE_END = LocalTime.of(15, 30);
    private static final String TAIL_CONFIRM_LABEL = "14:57-15:00";
    private static final ShortTermWeightProfile WEIGHT_PROFILE = new ShortTermWeightProfile(
            new BigDecimal("0.10"),
            new BigDecimal("0.30"),
            new BigDecimal("0.25"),
            new BigDecimal("0.35"),
            new BigDecimal("0.40"),
            new BigDecimal("0.20"),
            new BigDecimal("0.15"),
            new BigDecimal("0.20"),
            new BigDecimal("0.05")
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
    private static final String QUOTE_NOTE = "短线右侧模块先做全 A 股行情漏斗，再对候选拉取近一年 K 线和最近年报指标；PE/PB 仅作低权重估值语境，最终以右侧形态、量能、热门方向和财报质量共同约束。";

    private final EastMoneyClient eastMoneyClient;
    private final EvidenceCompletenessService evidenceCompletenessService;
    private final TradingClockService tradingClockService;
    private final QuoteFreshnessService quoteFreshnessService;
    private final ValuationContextCalculator valuationContextCalculator = new ValuationContextCalculator();

    public ShortTermService(EastMoneyClient eastMoneyClient) {
        this(eastMoneyClient, new EvidenceCompletenessService(), new TradingClockService());
    }

    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService
    ) {
        this(eastMoneyClient, evidenceCompletenessService, tradingClockService, new QuoteFreshnessService(tradingClockService));
    }

    @Autowired
    public ShortTermService(
            EastMoneyClient eastMoneyClient,
            EvidenceCompletenessService evidenceCompletenessService,
            TradingClockService tradingClockService,
            QuoteFreshnessService quoteFreshnessService
    ) {
        this.eastMoneyClient = eastMoneyClient;
        this.evidenceCompletenessService = evidenceCompletenessService;
        this.tradingClockService = tradingClockService;
        this.quoteFreshnessService = quoteFreshnessService;
    }

    public ShortTermReport report(
            Integer limit,
            Integer scanLimit,
            Integer klineLimit,
            BigDecimal minAmount,
            BigDecimal maxPe,
            BigDecimal maxPb,
            BigDecimal minVolumeRatio,
            BigDecimal maxEntryRise,
            BigDecimal maxDistanceToMa20,
            BigDecimal minFinancialScore
    ) {
        ShortTermRuleSet ruleSet = resolveRuleSet(
                scanLimit,
                klineLimit,
                minAmount,
                maxPe,
                maxPb,
                minVolumeRatio,
                maxEntryRise,
                maxDistanceToMa20,
                minFinancialScore
        );

        AshareQuoteSnapshot quoteSnapshot = fetchMarketQuoteSnapshot(ruleSet.scanLimit());
        List<EastMoneyQuote> marketQuotes = quoteSnapshot.quotes();
        List<EastMoneyQuote> quoteUniverse = marketQuotes.stream()
                .filter(this::isTradableCommonShare)
                .filter(this::hasUsablePrice)
                .toList();
        Set<String> unstableIndustrySymbols = fetchUnstableIndustrySymbols();
        List<ShortTermRiskExclusion> exclusions = quoteUniverse.stream()
                .map(quote -> preFilterExclusion(quote, ruleSet, unstableIndustrySymbols))
                .filter(exclusion -> exclusion != null)
                .limit(40)
                .toList();
        List<EastMoneyQuote> preFilteredQuotes = quoteUniverse.stream()
                .filter(quote -> passesQuotePreFilter(quote, ruleSet, unstableIndustrySymbols))
                .toList();
        ShortTermMarketSentiment marketSentiment = marketSentiment(quoteUniverse, quoteSnapshot);
        List<ShortTermHotDirection> hotDirections = resolveHotDirections(preFilteredQuotes);
        Map<String, ShortTermHotDirection> hotDirectionMap = hotDirections.stream()
                .collect(Collectors.toMap(
                        ShortTermHotDirection::code,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<EastMoneyQuote> tradableQuotes = preFilteredQuotes.stream()
                .sorted(Comparator.comparing((EastMoneyQuote quote) -> preliminaryScore(quote, ruleSet, hotDirectionMap)).reversed())
                .limit(ruleSet.klineLimit())
                .toList();

        Map<String, List<EastMoneyKLine>> klineMap = tradableQuotes.parallelStream()
                .collect(Collectors.toMap(
                        EastMoneyQuote::symbol,
                        quote -> fetchKLinesSafely(quote.symbol()),
                        (left, right) -> left
                ));

        List<TechnicalCandidate> technicalCandidates = tradableQuotes.stream()
                .map(quote -> technicalCandidate(quote, klineMap.getOrDefault(quote.symbol(), List.of()), ruleSet))
                .filter(candidate -> candidate.technical().snapshot().tradeDate() != null)
                .filter(candidate -> !isLongSidewaysWithoutBreakout(candidate, ruleSet))
                .sorted(Comparator.comparing((TechnicalCandidate item) -> item.technicalScore()
                        .multiply(new BigDecimal("0.70"))
                        .add(item.volumeScore().multiply(new BigDecimal("0.30")))).reversed())
                .limit(Math.max(resolveLimit(limit) * 4L, 28L))
                .toList();

        Map<String, ShortTermFinancialSnapshot> financialMap = technicalCandidates.parallelStream()
                .collect(Collectors.toMap(
                        item -> item.quote().symbol(),
                        item -> financialSnapshot(item.quote().symbol()),
                        (left, right) -> left
                ));

        List<ScoredShortTerm> scored = technicalCandidates.stream()
                .map(item -> score(item, financialMap.get(item.quote().symbol()), ruleSet, hotDirectionMap, marketSentiment))
                .sorted(Comparator.comparingInt((ScoredShortTerm item) -> actionPriority(item.candidate().action())).reversed()
                        .thenComparing(item -> item.candidate().score().finalScore(), Comparator.reverseOrder()))
                .toList();

        int safeLimit = Math.min(resolveLimit(limit), scored.size());
        List<ShortTermCandidate> candidates = IntStream.range(0, safeLimit)
                .mapToObj(index -> enrichTailSignal(rerank(scored.get(index).candidate(), index + 1)))
                .toList();

        long validKlineCount = technicalCandidates.stream()
                .filter(candidate -> candidate.technical().snapshot().tradeDate() != null)
                .count();

        return new ShortTermReport(
                "A 股短线右侧启动池",
                marketQuotes.size(),
                tradableQuotes.size(),
                (int) validKlineCount,
                candidates.size(),
                quoteNote(quoteSnapshot),
                tradingClockService.currentSession(),
                List.of(
                        "第一层用全 A 股行情做流动性、非 ST、热门方向和追涨风险排序，PE/PB 只占预选分的 10%。",
                        "第二层拉取候选近一年 K 线，优先寻找站上 20 日线、20 日线走平上拐、突破前高且距离均线不远的右侧早期结构。",
                        "第三层用量能确认突破强度；量比不足只能观察，不能因为低 PE/PB 直接给买入建议。",
                        "PE、PB 在最终分中只占 5%，高于参考带仅提示预期偏高，不能单独淘汰右侧候选。",
                        "当前热门方向会提高候选优先级，但不能覆盖 K 线结构、量能确认和财报质量。",
                        "涨幅过大、距离 20 日线过远、120 日位置过高、量能过度放大会被视为追涨风险，宁可等待回踩确认。",
                        "最终建议只做短线纪律提示：右侧早期也以分批试错为主，跌破关键均线要退出。",
                        "14:57 前只筛候选不执行买点；14:57-15:00 收盘集合竞价必须复核分时、均价线、尾盘成交占比和高点回落。15:05-15:30 盘后固定价格单独分析，不能混作普通尾盘买点。"
                ),
                ruleSet,
                WEIGHT_PROFILE,
                candidates,
                hotDirections,
                marketSentiment,
                exclusions,
                Instant.now()
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

    private String quoteNote(AshareQuoteSnapshot snapshot) {
        String coverage = snapshot.fetchedCount() + "/" + snapshot.expectedCount();
        if (snapshot.complete()) {
            return QUOTE_NOTE + " 本轮行情覆盖 " + coverage + "，来源：" + snapshot.source() + "。";
        }
        return QUOTE_NOTE + " 本轮行情覆盖不足 " + coverage + "，缺失 " + snapshot.missingCount()
                + " 只；页面保留研究结果，但覆盖率低于 90% 时关闭短线执行动作。来源：" + snapshot.source() + "。";
    }

    private TechnicalCandidate technicalCandidate(EastMoneyQuote quote, List<EastMoneyKLine> klines, ShortTermRuleSet ruleSet) {
        TechnicalContext context = technicalContext(quote, klines, ruleSet);
        BigDecimal technicalScore = technicalScore(context, ruleSet);
        BigDecimal volumeScore = volumeScore(context.snapshot(), ruleSet);
        return new TechnicalCandidate(quote, context, technicalScore, volumeScore);
    }

    private ScoredShortTerm score(
            TechnicalCandidate item,
            ShortTermFinancialSnapshot financial,
            ShortTermRuleSet ruleSet,
            Map<String, ShortTermHotDirection> hotDirectionMap,
            ShortTermMarketSentiment marketSentiment
    ) {
        EastMoneyQuote quote = item.quote();
        ShortTermTechnicalSnapshot technical = item.technical().snapshot();
        ValuationContext valuationContext = valuationContextCalculator.evaluate(
                pe(quote),
                quote.pbRatio(),
                ruleSet.maxPe(),
                ruleSet.maxPb(),
                quote.industry()
        );
        BigDecimal valuationScore = valuationContext.score();
        BigDecimal financialScore = financial == null ? new BigDecimal("40") : financial.qualityScore();
        QuoteFreshnessSnapshot quoteFreshness = quoteFreshnessService.evaluate(quote);
        BigDecimal marketHeatScore = marketHeatScore(quote, hotDirectionMap);
        BigDecimal riskPenalty = riskPenalty(quote, technical, financial, ruleSet);
        BigDecimal finalScore = clamp(
                item.technicalScore().multiply(WEIGHT_PROFILE.finalTechnical())
                        .add(item.volumeScore().multiply(WEIGHT_PROFILE.finalVolume()))
                        .add(marketHeatScore.multiply(WEIGHT_PROFILE.finalHeat()))
                        .add(financialScore.multiply(WEIGHT_PROFILE.finalFinancial()))
                        .add(valuationScore.multiply(WEIGHT_PROFILE.finalValuation()))
                        .subtract(riskPenalty)
        );
        ActionDecision decision = decide(quote, technical, financial, valuationContext, quoteFreshness, marketSentiment, item.technicalScore(), item.volumeScore(), finalScore, ruleSet);
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
                valuationContext,
                phase(technical, decision),
                phaseLabel(technical, decision),
                decision.action(),
                decision.actionLabel(),
                reason(quote, technical, financial, decision),
                todayAdvice(decision, quote, technical, finalScore, ruleSet),
                pendingTailSignal(),
                new ShortTermScoreBreakdown(item.technicalScore(), item.volumeScore(), marketHeatScore, valuationScore, financialScore, riskPenalty, finalScore),
                technical,
                financial,
                buyZoneLow(price, technical),
                buyZoneHigh(price, technical, ruleSet),
                stopPrice(price, technical),
                strengths(quote, technical, financial, valuationContext),
                risks(quote, technical, financial, ruleSet, valuationContext, quoteFreshness),
                entryRules(decision, ruleSet),
                exitRules(ruleSet),
                pendingEvidenceCompleteness(quote, technical, financial, quoteFreshness),
                evidence(quote, technical, financial, item.technical(), valuationContext, quoteFreshness)
        );
        return new ScoredShortTerm(candidate);
    }

    private TechnicalContext technicalContext(EastMoneyQuote quote, List<EastMoneyKLine> klines, ShortTermRuleSet ruleSet) {
        List<EastMoneyKLine> sorted = klines == null ? List.of() : klines.stream()
                .filter(kline -> kline.close() != null && kline.tradeDate() != null)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (sorted.size() < 35) {
            return new TechnicalContext(
                    quote,
                    sorted,
                    new ShortTermTechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, 0, "K线不足"),
                    null,
                    null,
                    List.of("近一年 K 线不足，不能确认右侧启动")
            );
        }

        EastMoneyKLine last = sorted.get(sorted.size() - 1);
        EastMoneyKLine previous = sorted.size() >= 2 ? sorted.get(sorted.size() - 2) : null;
        List<EastMoneyKLine> previousRows = sorted.subList(0, sorted.size() - 1);
        BigDecimal ma5 = movingAverage(sorted, 5);
        BigDecimal ma10 = movingAverage(sorted, 10);
        BigDecimal ma20 = movingAverage(sorted, 20);
        BigDecimal ma60 = movingAverage(sorted, 60);
        BigDecimal ma20Slope = movingAverageSlope(sorted, 20, 5);
        BigDecimal ma60Slope = movingAverageSlope(sorted, 60, 10);
        BigDecimal previousHigh20 = high(previousRows, 20);
        BigDecimal previousHigh60 = high(previousRows, 60);
        BigDecimal previousLow20 = low(previousRows, 20);
        BigDecimal high120 = high(sorted, 120);
        BigDecimal low120 = low(sorted, 120);
        BigDecimal low60 = low(sorted, 60);
        BigDecimal high60 = high(sorted, 60);
        BigDecimal close = latestClose(quote, last);
        BigDecimal volumeRatio5 = volumeRatio(sorted, 5);
        BigDecimal volumeRatio20 = volumeRatio(sorted, 20);
        BigDecimal range60 = rangePosition(close, low60, high60);
        BigDecimal range120 = rangePosition(close, low120, high120);
        BigDecimal distanceToMa20 = percent(close.subtract(nullToZero(ma20)), ma20);
        BigDecimal breakoutFromPreviousHigh20 = percent(close.subtract(nullToZero(previousHigh20)), previousHigh20);
        BigDecimal previousRange20 = percent(nullToZero(previousHigh20).subtract(nullToZero(previousLow20)), previousLow20);
        BigDecimal drawdownFromHigh120 = percent(nullToZero(high120).subtract(close), high120);
        BigDecimal amplitude = last.high() == null || last.low() == null ? null : percent(last.high().subtract(last.low()), close);
        int consecutiveAboveMa20 = consecutiveAboveMa(sorted, 20);
        String rightSideSignal = rightSideSignal(
                close,
                previous,
                ma5,
                ma10,
                ma20,
                ma60,
                ma20Slope,
                previousHigh20,
                range60,
                distanceToMa20,
                breakoutFromPreviousHigh20,
                volumeRatio20,
                ruleSet
        );

        return new TechnicalContext(
                quote,
                sorted,
                new ShortTermTechnicalSnapshot(
                        last.tradeDate(),
                        scale(ma5),
                        scale(ma10),
                        scale(ma20),
                        scale(ma60),
                        scale(ma20Slope),
                        scale(ma60Slope),
                        scale(previousHigh20),
                        scale(previousHigh60),
                        scale(breakoutFromPreviousHigh20),
                        scale(previousRange20),
                        scale(high120),
                        scale(low120),
                        scale(volumeRatio5),
                        scale(volumeRatio20),
                        scale(range60),
                        scale(range120),
                        scale(distanceToMa20),
                        scale(drawdownFromHigh120),
                        scale(amplitude),
                        consecutiveAboveMa20,
                        rightSideSignal
                ),
                last,
                previous,
                List.of()
        );
    }

    private String rightSideSignal(
            BigDecimal close,
            EastMoneyKLine previous,
            BigDecimal ma5,
            BigDecimal ma10,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma20SlopePercent,
            BigDecimal previousHigh20,
            BigDecimal range60,
            BigDecimal distanceToMa20,
            BigDecimal breakoutFromPreviousHigh20Percent,
            BigDecimal volumeRatio20,
            ShortTermRuleSet ruleSet
    ) {
        if (close == null || ma20 == null) {
            return "K线不足";
        }
        boolean aboveMa20 = close.compareTo(ma20) > 0;
        boolean ma5AboveMa10 = ma5 != null && ma10 != null && ma5.compareTo(ma10) >= 0;
        boolean ma20Turning = ma20SlopePercent != null && ma20SlopePercent.compareTo(new BigDecimal("-0.20")) >= 0;
        boolean nearMa20 = distanceToMa20 != null && distanceToMa20.compareTo(BigDecimal.ZERO) >= 0 && distanceToMa20.compareTo(ruleSet.maxDistanceToMa20Percent()) <= 0;
        boolean middleRange = range60 != null && range60.compareTo(new BigDecimal("35")) >= 0 && range60.compareTo(new BigDecimal("92")) <= 0;
        boolean volumeConfirmed = hasVolumeConfirmation(volumeRatio20, ruleSet);
        boolean crossedMa20 = previous != null && previous.close() != null && previous.close().compareTo(ma20) <= 0 && aboveMa20;
        boolean breakout20 = previousHigh20 != null && close.compareTo(previousHigh20) >= 0
                || breakoutFromPreviousHigh20Percent != null && breakoutFromPreviousHigh20Percent.compareTo(BigDecimal.ZERO) >= 0;
        boolean aboveMa60 = ma60 != null && close.compareTo(ma60) > 0;

        if (aboveMa20 && ma5AboveMa10 && ma20Turning && nearMa20 && middleRange && volumeConfirmed && (crossedMa20 || breakout20)) {
            return "右侧早期确认";
        }
        if (aboveMa20 && ma5AboveMa10 && ma20Turning && nearMa20 && middleRange) {
            return "右侧早期观察";
        }
        if (aboveMa20 && aboveMa60 && !nearMa20) {
            return "右侧已拉开";
        }
        if (aboveMa20) {
            return "右侧雏形";
        }
        return "尚未右侧";
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
        if (snapshot.ma5() != null && snapshot.ma10() != null && snapshot.ma5().compareTo(snapshot.ma10()) >= 0) {
            score = score.add(new BigDecimal("8"));
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

    private BigDecimal volumeScore(ShortTermTechnicalSnapshot snapshot, ShortTermRuleSet ruleSet) {
        BigDecimal ratio = snapshot.volumeRatio20();
        if (ratio == null) {
            return new BigDecimal("45");
        }
        if (ratio.compareTo(ruleSet.minVolumeRatio()) >= 0 && ratio.compareTo(new BigDecimal("2.80")) <= 0) {
            return new BigDecimal("86");
        }
        if (ratio.compareTo(BigDecimal.ONE) >= 0 && ratio.compareTo(ruleSet.minVolumeRatio()) < 0) {
            return new BigDecimal("66");
        }
        if (ratio.compareTo(new BigDecimal("2.80")) > 0 && ratio.compareTo(new BigDecimal("4.20")) <= 0) {
            return new BigDecimal("68");
        }
        if (ratio.compareTo(new BigDecimal("4.20")) > 0) {
            return new BigDecimal("42");
        }
        return new BigDecimal("50");
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
            ValuationContext valuationContext,
            QuoteFreshnessSnapshot quoteFreshness,
            ShortTermMarketSentiment marketSentiment,
            BigDecimal technicalScore,
            BigDecimal volumeScore,
            BigDecimal finalScore,
            ShortTermRuleSet ruleSet
    ) {
        if (quoteFreshness.blocksRealtimeDecision()) {
            return new ActionDecision("DATA_REVIEW", quoteFreshness.statusLabel());
        }
        boolean hasFinancial = financial != null && financial.reportDate() != null;
        boolean financialOk = financial != null && financial.qualityScore().compareTo(ruleSet.minFinancialScore()) >= 0;
        boolean rightEarly = "右侧早期确认".equals(technical.rightSideSignal()) || "右侧早期观察".equals(technical.rightSideSignal());
        boolean volumeConfirmed = hasVolumeConfirmation(technical.volumeRatio20(), ruleSet);
        boolean chaseRisk = isChaseRisk(quote, technical, ruleSet);
        boolean crowdedSentiment = "高潮".equals(marketSentiment.phase());
        boolean marketRiskOff = "退潮".equals(marketSentiment.phase())
                || "冰点/混沌".equals(marketSentiment.phase())
                || "行情覆盖不足".equals(marketSentiment.phase());
        if (valuationContext.state() == ValuationContextState.DISTORTED) {
            return new ActionDecision("VALUATION_REVIEW", "周期估值复核");
        }
        if (valuationContext.state() == ValuationContextState.MISSING) {
            return new ActionDecision("VALUATION_REVIEW", "估值证据不足");
        }
        if (marketRiskOff) {
            return new ActionDecision("MARKET_RISK_WAIT", "情绪风险等待");
        }
        // 右侧策略优先识别“早期启动 + 基本面可接受”的机会；总分只决定信心，不再作为过高的单一闸门。
        boolean qualityEnoughForTrial = financial != null
                && financial.qualityScore().compareTo(ruleSet.minFinancialScore().subtract(new BigDecimal("5"))) >= 0;
        if (rightEarly && !crowdedSentiment && technicalScore.compareTo(new BigDecimal("65")) >= 0
                && volumeConfirmed && volumeScore.compareTo(new BigDecimal("70")) >= 0
                && qualityEnoughForTrial && !chaseRisk
                && "右侧早期确认".equals(technical.rightSideSignal())) {
            return new ActionDecision("RIGHT_EARLY_ADD", "右侧早期-分批");
        }
        if ((rightEarly || "右侧已拉开".equals(technical.rightSideSignal())) && chaseRisk) {
            return new ActionDecision("WAIT_PULLBACK", "右侧已动-等回踩");
        }
        if (rightEarly && finalScore.compareTo(new BigDecimal("62")) >= 0 && financialOk) {
            return new ActionDecision("WATCH_RIGHT_SIDE", "右侧观察");
        }
        if (!hasFinancial || technical.tradeDate() == null) {
            return new ActionDecision("DATA_REVIEW", "数据复核");
        }
        return new ActionDecision("WAIT_CONFIRM", "等待确认");
    }

    private ShortTermMarketSentiment marketSentiment(List<EastMoneyQuote> quotes, AshareQuoteSnapshot snapshot) {
        if (!hasReliableMarketCoverage(snapshot)) {
            return new ShortTermMarketSentiment(
                    "行情覆盖不足",
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    0,
                    0,
                    0,
                    0,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    "行情仅覆盖 " + snapshot.fetchedCount() + "/" + snapshot.expectedCount()
                            + "，不足以代表全市场广度，本轮关闭短线执行动作。"
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
        String phase;
        if (limitDownLike >= 30 || (breadth.compareTo(new BigDecimal("35")) < 0 && limitDownLike > limitUpLike * 2)) {
            phase = "退潮";
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
        return new ShortTermMarketSentiment(phase, score.setScale(2, RoundingMode.HALF_UP), advancing, declining,
                limitUpLike, limitDownLike, breadth,
                "基于全市场涨跌广度、涨停近似数和跌停近似数；情绪状态只用于仓位和加仓闸门，不单独产生买点。");
    }

    private boolean hasReliableMarketCoverage(AshareQuoteSnapshot snapshot) {
        if (snapshot == null || snapshot.expectedCount() <= 0) {
            return false;
        }
        if (snapshot.complete()) {
            return true;
        }
        BigDecimal ratio = BigDecimal.valueOf(snapshot.fetchedCount())
                .divide(BigDecimal.valueOf(snapshot.expectedCount()), 4, RoundingMode.HALF_UP);
        return ratio.compareTo(MIN_RELIABLE_MARKET_COVERAGE) >= 0;
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
                            "量能温和放大，不是纯题材情绪急拉。",
                            "估值和最近年报质量没有明显冲突。"
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
        if ("WATCH_RIGHT_SIDE".equals(decision.action()) || "WATCH_VALUE_RETURN".equals(decision.action())) {
            return new TradingAdvice(
                    "WAIT",
                    "观望",
                    confidence(finalScore),
                    "右侧雏形存在，但量能或突破强度还没有形成强确认，短线不因为估值低就直接买。",
                    List.of("趋势开始转强。", "财报没有明显否决，估值只作低权重语境提示。"),
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
        TradingAdvice adjustedAdvice = evidenceCompletenessService.gateAdvice(
                tailAdjustedAdvice(candidate.todayAdvice(), candidate.action(), tailSignal),
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
                candidate.valuationContext(),
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
                withTailEvidence(candidate.evidence(), tailSignal)
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
                    List.of("当天 1 分钟分时数据暂不可用，不能确认 14:57-15:00 收盘承接。"),
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
                    List.of("当天 1 分钟分时为空，无法判断 14:57-15:00 收盘承接。"),
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
        EastMoneyIntradayPoint latest = points.get(points.size() - 1);
        String tradeDate = latest.minute().toLocalDate().toString();
        String latestMinute = latest.minute().toLocalTime().toString();
        if (latest.minute().toLocalTime().isBefore(TAIL_CONFIRM_TIME)) {
            return new ShortTermTailSignal(
                    "NOT_READY",
                    "等14:57",
                    false,
                    tradeDate,
                    latestMinute,
                    money(latest.close()),
                    null,
                    null,
                    null,
                    latest.averagePrice() == null ? null : scale(percent(latest.close().subtract(latest.averagePrice()), latest.averagePrice())),
                    null,
                    null,
                    new BigDecimal("40"),
                    List.of("当前最新分时到 " + latestMinute + "，尚未进入 14:57-15:00 收盘集合竞价确认窗口。"),
                    List.of("14:57 前只筛候选，不把盘中涨幅当作买点。", "等收盘集合竞价站稳均价线且回落幅度可控后再评估。")
            );
        }

        List<EastMoneyIntradayPoint> tailPoints = points.stream()
                .filter(point -> !point.minute().toLocalTime().isBefore(TAIL_CONFIRM_TIME))
                .filter(point -> !point.minute().toLocalTime().isAfter(REGULAR_CLOSE_TIME))
                .toList();
        if (tailPoints.isEmpty() && isPostCloseFixedPriceMinute(latest.minute().toLocalTime())) {
            return new ShortTermTailSignal(
                    "POST_CLOSE_FIXED_PRICE",
                    "盘后固定价",
                    false,
                    tradeDate,
                    latestMinute,
                    money(latest.close()),
                    null,
                    null,
                    null,
                    latest.averagePrice() == null ? null : scale(percent(latest.close().subtract(latest.averagePrice()), latest.averagePrice())),
                    null,
                    null,
                    new BigDecimal("38"),
                    List.of("最新分时到 " + latestMinute + "，属于 15:05-15:30 盘后固定价格区间，不是普通竞价尾盘确认。"),
                    List.of("盘后固定价格不能和普通尾盘买点混用。", "需要回看 14:57-15:00 收盘集合竞价数据后再判断。")
            );
        }
        if (tailPoints.isEmpty()) {
            return new ShortTermTailSignal(
                    "UNAVAILABLE",
                    "收盘分时缺失",
                    false,
                    tradeDate,
                    latestMinute,
                    money(latest.close()),
                    null,
                    null,
                    null,
                    latest.averagePrice() == null ? null : scale(percent(latest.close().subtract(latest.averagePrice()), latest.averagePrice())),
                    null,
                    null,
                    new BigDecimal("35"),
                    List.of("缺少 14:57-15:00 收盘集合竞价分时，不能确认尾盘买点。"),
                    List.of("收盘集合竞价分时缺失时不执行短线买入。")
            );
        }
        EastMoneyIntradayPoint start = tailPoints.get(0);
        latest = tailPoints.get(tailPoints.size() - 1);
        latestMinute = latest.minute().toLocalTime().toString();
        BigDecimal tailHigh = tailPoints.stream()
                .map(point -> point.high() == null ? point.close() : point.high())
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(latest.close());
        BigDecimal tailAmount = tailPoints.stream()
                .map(point -> point.amount() == null ? BigDecimal.ZERO : point.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = points.stream()
                .map(point -> point.amount() == null ? BigDecimal.ZERO : point.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal changeFromTailConfirm = scale(percent(latest.close().subtract(start.close()), start.close()));
        BigDecimal drawdown = tailHigh == null ? null : scale(percent(tailHigh.subtract(latest.close()), tailHigh));
        BigDecimal closeVsAverage = latest.averagePrice() == null ? null : scale(percent(latest.close().subtract(latest.averagePrice()), latest.averagePrice()));
        BigDecimal tailAmountRatio = totalAmount.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : scale(tailAmount.multiply(new BigDecimal("100")).divide(totalAmount, 6, RoundingMode.HALF_UP));
        BigDecimal score = tailScore(changeFromTailConfirm, drawdown, closeVsAverage, tailAmountRatio, tailPoints.size());
        BigDecimal tailAmountRatioThreshold = tailAmountRatioThreshold(totalAmount);
        String status;
        String label;
        if (tailPoints.size() >= 3
                && gte(changeFromTailConfirm, "0.30")
                && lte(drawdown, "1.20")
                && gte(closeVsAverage, "0.00")
                && tailAmountRatio != null
                && tailAmountRatio.compareTo(tailAmountRatioThreshold) >= 0) {
            status = "CONFIRMED";
            label = "收盘确认";
        } else if (gte(changeFromTailConfirm, "0.00")
                && lte(drawdown, "1.80")
                && gte(closeVsAverage, "-0.25")) {
            status = "WATCH";
            label = "尾盘观察";
        } else {
            status = "WEAK";
            label = "尾盘回落";
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("14:57-15:00 涨跌 " + valueText(changeFromTailConfirm) + "%，收盘集合竞价高点回落 " + valueText(drawdown) + "%。");
        reasons.add("最新价相对当日均价线 " + valueText(closeVsAverage) + "%，尾盘成交额占比 " + valueText(tailAmountRatio)
                + "%，本票动态确认门槛 " + valueText(tailAmountRatioThreshold) + "%。");
        if ("CONFIRMED".equals(status)) {
            reasons.add("价格、均价线和尾盘成交占比同时满足试错确认。");
        } else if ("WATCH".equals(status)) {
            reasons.add("尾盘没有明显走坏，但强度不足，只能继续观察。");
        } else {
            reasons.add("尾盘回落或均价线承接不足，不适合作为 14:57-15:00 收盘买点。");
        }
        return new ShortTermTailSignal(
                status,
                label,
                true,
                tradeDate,
                latestMinute,
                money(latest.close()),
                money(start.close()),
                changeFromTailConfirm,
                drawdown,
                closeVsAverage,
                money(tailAmount),
                tailAmountRatio,
                score,
                reasons,
                List.of("只用 14:57-15:00 收盘集合竞价执行普通尾盘确认。", "尾盘跌回均价线下方或从高点回落超过 1.8% 时不追。", "大成交额股票采用较低尾盘成交占比门槛，但仍必须站稳均价线并保持价格强度。", "15:05-15:30 盘后固定价格单独分析，不混作普通买点。")
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
            BigDecimal changeFromTailConfirm,
            BigDecimal drawdown,
            BigDecimal closeVsAverage,
            BigDecimal tailAmountRatio,
            int minuteCount
    ) {
        BigDecimal score = new BigDecimal("50")
                .add(nullToZero(changeFromTailConfirm).multiply(new BigDecimal("12")))
                .subtract(nullToZero(drawdown).multiply(new BigDecimal("10")))
                .add(nullToZero(closeVsAverage).multiply(new BigDecimal("8")))
                .add(nullToZero(tailAmountRatio).min(new BigDecimal("18")).multiply(new BigDecimal("0.80")))
                .add(BigDecimal.valueOf(Math.min(minuteCount, 20)).multiply(new BigDecimal("0.30")));
        return scale(clamp(score));
    }

    private TradingAdvice tailAdjustedAdvice(TradingAdvice base, String candidateAction, ShortTermTailSignal tailSignal) {
        if ("CONFIRMED".equals(tailSignal.status()) && "ADD".equals(base.action())) {
            return new TradingAdvice(
                    "ADD",
                    "加仓",
                    Math.min(90, Math.max(base.confidence(), tailSignal.score().setScale(0, RoundingMode.HALF_UP).intValue())),
                    "右侧结构通过，且 14:57-15:00 收盘集合竞价承接确认，可按纪律小仓试错。",
                    merge(base.reasons(), tailSignal.reasons()),
                    merge(base.riskControls(), tailSignal.riskControls())
            );
        }
        if (isPullbackAdvice(base)) {
            return base;
        }
        if (shouldLightTrial(base, candidateAction, tailSignal)) {
            return new TradingAdvice(
                    "LIGHT_TRIAL",
                    "轻仓试错",
                    lightTrialConfidence(base, tailSignal),
                    "右侧结构已经进入可试错区，但还没有达到强加仓标准，只允许轻仓试错，不追第二笔。",
                    merge(List.of("日线右侧结构进入观察区。", "14:57-15:00 尾盘没有明显走坏，具备小仓验证条件。"), base.reasons()),
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
        if ("CONFIRMED".equals(tailSignal.status())) {
            return new TradingAdvice(
                    "NEXT_WATCH",
                    "次日关注",
                    nextWatchConfidence(base),
                    "收盘集合竞价分时较强，但日线结构或量能条件仍未达到买入阈值，先放入次日关注，不只凭尾盘拉升追入。",
                    merge(List.of("14:57-15:00 承接较好。"), base.reasons()),
                    merge(base.riskControls(), List.of("日线结构未过买入阈值时，尾盘确认也只作为次日关注。"))
            );
        }
        String summary = switch (tailSignal.status()) {
            case "NOT_READY" -> "当前还没有进入 14:57-15:00 收盘集合竞价确认窗口，短线买点需要等当天分时数据完成验证。";
            case "POST_CLOSE_FIXED_PRICE" -> "当前数据属于盘后固定价格区间，不能和普通尾盘买点混用，今日先观察。";
            case "WATCH" -> "14:57-15:00 分时没有明显走坏，但确认强度不足，今日先观察。";
            case "WEAK" -> "14:57-15:00 分时承接不足或从高点回落，今日不追。";
            default -> "当天分时数据缺失，无法确认 " + TAIL_CONFIRM_LABEL + " 后买点。";
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

    private boolean shouldLightTrial(TradingAdvice base, String candidateAction, ShortTermTailSignal tailSignal) {
        return isRightSideExecutableCandidate(base, candidateAction)
                && ("CONFIRMED".equals(tailSignal.status()) || "WATCH".equals(tailSignal.status()));
    }

    private boolean shouldNextDayWatch(TradingAdvice base, String candidateAction, ShortTermTailSignal tailSignal) {
        return isRightSideExecutableCandidate(base, candidateAction)
                && ("WEAK".equals(tailSignal.status()) || "POST_CLOSE_FIXED_PRICE".equals(tailSignal.status()));
    }

    private boolean isRightSideExecutableCandidate(TradingAdvice base, String candidateAction) {
        return "ADD".equals(base.action())
                || "RIGHT_EARLY_ADD".equals(candidateAction)
                || "WATCH_RIGHT_SIDE".equals(candidateAction);
    }

    private boolean isPullbackAdvice(TradingAdvice base) {
        return "WAIT_PULLBACK".equals(base.action());
    }

    private int lightTrialConfidence(TradingAdvice base, ShortTermTailSignal tailSignal) {
        int tailScore = tailSignal.score() == null ? 60 : tailSignal.score().setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(58, Math.min(78, Math.max(base.confidence(), tailScore)));
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
                List.of("14:57-15:00 收盘集合竞价信号会在候选入围后拉取。"),
                List.of("未完成收盘集合竞价确认前不执行短线买入。")
        );
    }

    private List<String> withTailEntryRule(List<String> rules) {
        List<String> merged = new ArrayList<>(rules);
        merged.add("14:57-15:00 必须复核收盘集合竞价分时：站稳均价线、尾盘回落可控、动态成交占比达标才允许加仓；尾盘仅观察时最多只能轻仓试错。");
        merged.add("15:05-15:30 盘后固定价格需要单独分析，不能替代普通尾盘确认。");
        return merged.stream().distinct().toList();
    }

    private List<ShortTermEvidence> withTailEvidence(List<ShortTermEvidence> evidence, ShortTermTailSignal tailSignal) {
        List<ShortTermEvidence> merged = new ArrayList<>(evidence);
        String minuteText = tailSignal.latestMinute() == null ? "待补充" : tailSignal.tradeDate() + " " + tailSignal.latestMinute();
        merged.add(new ShortTermEvidence(
                "尾盘分时",
                tailSignal.statusLabel() + "：最新分时 " + minuteText
                        + "，14:57-15:00 涨跌 " + valueText(tailSignal.changeFromTailConfirmPercent())
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
                pe(quote) != null && quote.pbRatio() != null,
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
                candidate.peTtm() != null && candidate.pbRatio() != null,
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

    private List<String> strengths(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            ValuationContext valuationContext
    ) {
        List<String> strengths = new ArrayList<>();
        if (technical.rightSideSignal() != null && technical.rightSideSignal().contains("右侧")) {
            strengths.add("K 线处于" + technical.rightSideSignal() + "，不是纯左侧猜底。");
        }
        if (technical.volumeRatio20() != null && technical.volumeRatio20().compareTo(new BigDecimal("1.10")) >= 0) {
            strengths.add("20 日量比约 " + technical.volumeRatio20() + "，有温和放量迹象。");
        }
        if (technical.ma20SlopePercent() != null && technical.ma20SlopePercent().compareTo(new BigDecimal("-0.20")) >= 0) {
            strengths.add("20 日线斜率约 " + technical.ma20SlopePercent() + "%，中短均线开始走平或上拐。");
        }
        if (technical.breakoutFromPreviousHigh20Percent() != null
                && technical.breakoutFromPreviousHigh20Percent().compareTo(BigDecimal.ZERO) >= 0
                && technical.breakoutFromPreviousHigh20Percent().compareTo(new BigDecimal("6")) <= 0) {
            strengths.add("价格突破前 20 日高点约 " + technical.breakoutFromPreviousHigh20Percent() + "%，突破幅度尚未过度拉开。");
        }
        strengths.add("PE/PB 仅形成 " + valuationContext.score() + " 分估值语境，不参与短线资格淘汰。");
        if (financial != null && financial.qualityScore().compareTo(new BigDecimal("58")) >= 0) {
            strengths.add(financial.statusLabel() + "，财报没有明显否决右侧交易。");
        }
        if (strengths.isEmpty()) {
            strengths.add("暂未形成强优势，只能作为观察样本。");
        }
        return strengths;
    }

    private List<String> risks(
            EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical,
            ShortTermFinancialSnapshot financial,
            ShortTermRuleSet ruleSet,
            ValuationContext valuationContext,
            QuoteFreshnessSnapshot quoteFreshness
    ) {
        List<String> risks = new ArrayList<>();
        if (quoteFreshness != null && quoteFreshness.blocksRealtimeDecision()) {
            risks.add(quoteFreshness.reason());
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
        if (technical.ma20SlopePercent() != null && technical.ma20SlopePercent().compareTo(new BigDecimal("-0.80")) < 0) {
            risks.add("20 日线仍在明显下行，右侧确认不足，不能把低估值当买点。");
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
        risks.addAll(valuationContext.warnings());
        if (risks.isEmpty()) {
            risks.add("短线交易仍受指数波动和板块轮动影响，需要严格止损。");
        }
        return risks;
    }

    private List<String> entryRules(ActionDecision decision, ShortTermRuleSet ruleSet) {
        if ("RIGHT_EARLY_ADD".equals(decision.action())) {
            return List.of(
                    "第一笔只在收盘站稳 20 日线且量比不低于 " + ruleSet.minVolumeRatio() + " 时执行。",
                    "20 日线必须走平上拐，且价格突破前 20 日高点，或缩量回踩 20 日线不破。",
                    "第二笔必须等待回踩不破 5/10/20 日线，不能在单日急拉后追。",
                    "财报质量分低于 " + ruleSet.minFinancialScore() + " 时，右侧信号只观察不买。"
            );
        }
        return List.of(
                "右侧信号未共振时不主动建仓。",
                "等温和放量突破前高，或缩量回踩关键均线不破。",
                "财报或 K 线关键证据缺失时只放观察池；PE/PB 缺失只降低估值语境置信度。"
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
            ValuationContext valuationContext,
            QuoteFreshnessSnapshot quoteFreshness
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
                "估值语境",
                "最新价 " + valueText(quote.latestPrice())
                        + "，PE TTM " + valueText(pe(quote))
                        + "，PB " + valueText(quote.pbRatio())
                        + "，状态 " + valuationContext.state().name()
                        + "；参考带只影响 5% 的最终得分，不参与资格淘汰。",
                quote.quoteUrl(),
                5
        ));
        evidence.add(new ShortTermEvidence(
                "K线结构",
                "信号为" + technical.rightSideSignal()
                        + "，距 20 日线 " + valueText(technical.distanceToMa20Percent())
                        + "%，20 日线斜率 " + valueText(technical.ma20SlopePercent())
                        + "%，突破前 20 日高点 " + valueText(technical.breakoutFromPreviousHigh20Percent())
                        + "%，20 日量比 " + valueText(technical.volumeRatio20()) + "。",
                quote.quoteUrl(),
                40
        ));
        if (financial == null || financial.reportDate() == null) {
            evidence.add(new ShortTermEvidence("财报质量", "最近年报指标暂不可用，不能确认价值回归。", null, 20));
        } else {
            evidence.add(new ShortTermEvidence(
                    "财报质量",
                    financial.reportDate() + " 年报：ROE " + ratioPercentText(financial.roe()) + "，经营现金流/股 " + valueText(financial.operatingCashFlowPerShare()) + "，质量分 " + financial.qualityScore() + "。",
                    null,
                    20
            ));
        }
        if (!context.dataGaps().isEmpty()) {
            evidence.add(new ShortTermEvidence("数据缺口", String.join("；", context.dataGaps()), null, 10));
        }
        return evidence;
    }

    private String reason(EastMoneyQuote quote, ShortTermTechnicalSnapshot technical, ShortTermFinancialSnapshot financial, ActionDecision decision) {
        String financialText = financial == null ? "财报待复核" : financial.statusLabel();
        return decision.actionLabel()
                + "："
                + technical.rightSideSignal()
                + "，PE/PB 为 "
                + valueText(pe(quote))
                + "/"
                + valueText(quote.pbRatio())
                + "，"
                + financialText
                + "；短线以右侧、量能和热门方向为触发，估值不再硬卡。";
    }

    private String phase(ShortTermTechnicalSnapshot technical, ActionDecision decision) {
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

    private boolean passesQuotePreFilter(EastMoneyQuote quote, ShortTermRuleSet ruleSet, Set<String> unstableIndustrySymbols) {
        return preFilterExclusion(quote, ruleSet, unstableIndustrySymbols) == null;
    }

    private ShortTermRiskExclusion preFilterExclusion(
            EastMoneyQuote quote,
            ShortTermRuleSet ruleSet,
            Set<String> unstableIndustrySymbols
    ) {
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
        if (quote.changePercent() != null && quote.changePercent().compareTo(ruleSet.maxEntryRisePercent().add(new BigDecimal("5"))) > 0) {
            return riskExclusion(
                    quote,
                    "CHASE_RISK",
                    "单日急拉",
                    "单日涨幅超过追涨上限 5 个百分点以上，右侧信号可能已经过热，先从候选池剔除。"
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
        BigDecimal valuation = valuationContextCalculator.evaluate(
                pe(quote),
                quote.pbRatio(),
                ruleSet.maxPe(),
                ruleSet.maxPb(),
                quote.industry()
        ).score();
        BigDecimal liquidity = liquidityScore(quote, ruleSet);
        BigDecimal marketHeat = marketHeatScore(quote, hotDirectionMap);
        BigDecimal nonChase = quote.changePercent() == null
                ? new BigDecimal("60")
                : quote.changePercent().compareTo(ruleSet.maxEntryRisePercent()) <= 0 ? new BigDecimal("82") : new BigDecimal("42");
        return valuation.multiply(WEIGHT_PROFILE.preliminaryValuation())
                .add(liquidity.multiply(WEIGHT_PROFILE.preliminaryLiquidity()))
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

    private List<EastMoneyKLine> fetchKLinesSafely(String symbol) {
        try {
            LocalDate end = LocalDate.now();
            return eastMoneyClient.fetchDailyKLines(symbol, end.minusDays(420), end);
        } catch (RuntimeException exception) {
            logger.warn("短线右侧 K 线获取失败：{}", symbol, exception);
            return List.of();
        }
    }

    private ShortTermRuleSet resolveRuleSet(
            Integer scanLimit,
            Integer klineLimit,
            BigDecimal minAmount,
            BigDecimal maxPe,
            BigDecimal maxPb,
            BigDecimal minVolumeRatio,
            BigDecimal maxEntryRise,
            BigDecimal maxDistanceToMa20,
            BigDecimal minFinancialScore
    ) {
        return new ShortTermRuleSet(
                Math.max(50, Math.min(scanLimit == null ? DEFAULT_SCAN_LIMIT : scanLimit, MAX_SCAN_LIMIT)),
                Math.max(10, Math.min(klineLimit == null ? DEFAULT_KLINE_LIMIT : klineLimit, MAX_KLINE_LIMIT)),
                RecommendationQuality.requiredAmount(positiveOrDefault(minAmount, DEFAULT_MIN_AMOUNT)),
                positiveOrDefault(maxPe, DEFAULT_MAX_PE),
                positiveOrDefault(maxPb, DEFAULT_MAX_PB),
                positiveOrDefault(minVolumeRatio, DEFAULT_MIN_VOLUME_RATIO),
                positiveOrDefault(maxEntryRise, DEFAULT_MAX_ENTRY_RISE),
                positiveOrDefault(maxDistanceToMa20, DEFAULT_MAX_DISTANCE_TO_MA20),
                positiveOrDefault(minFinancialScore, DEFAULT_MIN_FINANCIAL_SCORE)
        );
    }

    private int resolveLimit(Integer limit) {
        return Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, 40));
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
                candidate.valuationContext(),
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
                candidate.evidence()
        );
    }

    private int actionPriority(String action) {
        return switch (action) {
            case "RIGHT_EARLY_ADD" -> 5;
            case "WATCH_RIGHT_SIDE", "WATCH_VALUE_RETURN" -> 4;
            case "WAIT_PULLBACK" -> 3;
            case "WAIT_CONFIRM" -> 2;
            default -> 1;
        };
    }

    private boolean isTradableCommonShare(EastMoneyQuote quote) {
        if (quote == null || quote.symbol() == null || quote.name() == null) {
            return false;
        }
        String name = quote.name().toUpperCase();
        if (name.contains("ST") || name.contains("退")
                || name.contains("官网") || name.contains("网站")
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
        return last.close();
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

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
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
            BigDecimal volumeScore
    ) {
    }

    private record ActionDecision(String action, String actionLabel) {
    }

    private record ScoredShortTerm(ShortTermCandidate candidate) {
    }
}

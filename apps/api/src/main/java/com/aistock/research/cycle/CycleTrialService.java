package com.aistock.research.cycle;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.RecommendationQuality;
import com.aistock.research.trading.TradingAdvice;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CycleTrialService {

    private static final Logger logger = LoggerFactory.getLogger(CycleTrialService.class);
    private static final int DEFAULT_LIMIT = 10;
    private static final int FULL_MARKET_SCAN_LIMIT = 6000;
    private static final int MIN_DEEP_REVIEW_LIMIT = 30;
    private static final int MAX_DEEP_REVIEW_LIMIT = 60;
    private static final int MAX_FUND_FLOW_SYMBOLS = 3;
    private static final int PEER_BOARD_FETCH_LIMIT = 12;
    private static final int PEER_DISPLAY_LIMIT = 5;
    private static final int MIN_PEER_VALUATION_COUNT = 3;
    private static final int PEER_FETCH_TIMEOUT_SECONDS = 5;
    private static final BigDecimal SIGNIFICANT_DISCOUNT_PERCENT = new BigDecimal("20");
    private static final CycleTrialRuleSet DEFAULT_RULE_SET = new CycleTrialRuleSet(
            new BigDecimal("65"),
            new BigDecimal("78"),
            new BigDecimal("6.00"),
            new BigDecimal("1.50"),
            new BigDecimal("6.00"),
            new BigDecimal("3.00")
    );
    private static final String QUOTE_NOTE = "行情优先使用东方财富，失败或缺失时切换腾讯行情；资金流使用东方财富 push2，接口受盘中风控影响时会降级为缺口证据；周期票的日内信号变化很快，模块会把左侧试仓和右侧追涨分开处理。";

    private final EastMoneyClient eastMoneyClient;

    public CycleTrialService(EastMoneyClient eastMoneyClient) {
        this.eastMoneyClient = eastMoneyClient;
    }

    public CycleTrialReport report(
            Integer limit,
            BigDecimal leftTrialScore,
            BigDecimal rightAddScore,
            BigDecimal maxChaseRise,
            BigDecimal minVolumeRatio
    ) {
        CycleTrialRuleSet ruleSet = resolveRuleSet(leftTrialScore, rightAddScore, maxChaseRise, minVolumeRatio);
        List<EastMoneyQuote> marketQuotes = eastMoneyClient.fetchAshareQuotes(FULL_MARKET_SCAN_LIMIT).stream()
                .filter(this::isTradableCommonShare)
                .filter(quote -> isCycleIndustry(quote.industry()))
                .toList();
        List<CycleSeed> universe = marketQuotes.stream().map(this::dynamicSeed).toList();
        Map<String, EastMoneyQuote> quotes = marketQuotes.stream()
                .collect(Collectors.toMap(EastMoneyQuote::symbol, Function.identity(), (left, right) -> left));
        int safeLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, Math.max(universe.size(), 1)));
        int reviewLimit = Math.min(
                MAX_DEEP_REVIEW_LIMIT,
                Math.max(MIN_DEEP_REVIEW_LIMIT, safeLimit * 6)
        );
        List<CycleSeed> reviewUniverse = universe.stream()
                .sorted(Comparator.comparing((CycleSeed seed) -> preliminaryCycleScore(quotes.get(seed.symbol()))).reversed())
                .limit(reviewLimit)
                .toList();
        List<String> symbols = reviewUniverse.stream().map(CycleSeed::symbol).toList();
        Map<String, CyclePeerValuationSnapshot> peerValuations = peerValuations(reviewUniverse, quotes);
        Map<String, List<EastMoneyKLine>> klineMap = symbols.parallelStream()
                .collect(Collectors.toMap(Function.identity(), this::fetchKLines));
        safeLimit = Math.min(safeLimit, reviewUniverse.size());
        List<ScoredSeed> preliminary = reviewUniverse.stream()
                .filter(seed -> passesRecommendationQuality(quotes.get(seed.symbol()), klineMap.getOrDefault(seed.symbol(), List.of())))
                .map(seed -> new ScoredSeed(
                        seed,
                        score(seed, quotes.get(seed.symbol()), klineMap.getOrDefault(seed.symbol(), List.of()), null, peerValuations.get(seed.symbol()), ruleSet)
                ))
                .sorted(Comparator.comparingInt((ScoredSeed item) -> actionPriority(item.candidate().action())).reversed()
                        .thenComparing((ScoredSeed item) -> item.candidate().score().finalScore(), Comparator.reverseOrder()))
                .toList();
        List<String> fundFlowSymbols = preliminary.stream()
                .limit(Math.min(safeLimit, MAX_FUND_FLOW_SYMBOLS))
                .map(item -> item.seed().symbol())
                .toList();
        Map<String, EastMoneyFundFlowSnapshot> fundFlowMap = fetchFundFlows(fundFlowSymbols);
        List<ScoredCycle> finalists = preliminary.stream()
                .limit(safeLimit)
                .map(item -> score(
                        item.seed(),
                        quotes.get(item.seed().symbol()),
                        klineMap.getOrDefault(item.seed().symbol(), List.of()),
                        fundFlowMap.get(item.seed().symbol()),
                        peerValuations.get(item.seed().symbol()),
                        ruleSet
                ))
                .sorted(Comparator.comparingInt((ScoredCycle item) -> actionPriority(item.candidate().action())).reversed()
                        .thenComparing((ScoredCycle item) -> item.candidate().score().finalScore(), Comparator.reverseOrder()))
                .toList();

        List<CycleTrialCandidate> candidates = IntStream.range(0, finalists.size())
                .mapToObj(index -> rerank(finalists.get(index).candidate(), index + 1))
                .toList();

        return new CycleTrialReport(
                "周期交易/左侧试仓池",
                universe.size(),
                candidates.size(),
                QUOTE_NOTE,
                List.of(
                        "核心资产看长期质量，周期票先看赔率：催化、低位、止损距离、量能验证。",
                        "左侧试仓允许小仓提前介入，但必须有近止损，不能把试仓当成重仓确认。",
                        "右侧启动要求站上关键均线并放量；如果单日涨幅过大，则只允许持仓继续或等回踩。",
                        "所有周期候选必须满足 8000 万以上成交额，并剔除长期横盘震荡且无趋势效率的标的。",
                        "评分 = 周期催化 30% + 价格位置 20% + 反转形态 25% + 量能/资金 15% + 估值 10%；估值会同时参考绝对 PE/PB 和同业头部均值。"
                ),
                ruleSet,
                candidates,
                Instant.now()
        );
    }

    private boolean isTradableCommonShare(EastMoneyQuote quote) {
        return quote != null && quote.symbol() != null && quote.latestPrice() != null
                && quote.name() != null && !quote.name().contains("ST")
                && quote.amount() != null;
    }

    private CycleSeed dynamicSeed(EastMoneyQuote quote) {
        String industry = quote.industry() == null ? "全市场周期观察" : quote.industry();
        int catalyst = 50;
        return new CycleSeed(quote.symbol(), quote.name(), industry, "行业价格、供需和盈利周期变化",
                catalyst,
                List.of("来自全 A 股周期行业动态候选", "周期催化保持中性，需结合行业价格、库存、产能和财报验证"),
                List.of("周期判断存在滞后", "单日上涨不等于周期反转"));
    }

    private boolean isCycleIndustry(String industry) {
        if (industry == null || industry.isBlank()) {
            return false;
        }
        return containsAny(industry,
                "农业", "农牧", "养殖", "种植", "林业", "渔业", "饲料", "食品加工",
                "煤炭", "石油", "油气", "天然气", "有色", "钢铁", "化工", "化纤",
                "水泥", "建材", "玻璃", "造纸", "航运", "港口", "电力", "电池", "光伏");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal preliminaryCycleScore(EastMoneyQuote quote) {
        if (quote == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal score = new BigDecimal("40");
        if (quote.amount() != null) {
            if (quote.amount().compareTo(new BigDecimal("500000000")) >= 0) {
                score = score.add(new BigDecimal("28"));
            } else if (quote.amount().compareTo(RecommendationQuality.MIN_RECOMMENDED_AMOUNT) >= 0) {
                score = score.add(new BigDecimal("18"));
            }
        }
        BigDecimal change = quote.changePercent();
        if (change != null && change.compareTo(new BigDecimal("-4")) >= 0 && change.compareTo(new BigDecimal("6")) <= 0) {
            score = score.add(new BigDecimal("12"));
        }
        if (firstPresent(quote.peTtm(), quote.peRatio()) != null || quote.pbRatio() != null) {
            score = score.add(new BigDecimal("8"));
        }
        return score;
    }

    private boolean passesRecommendationQuality(EastMoneyQuote quote, List<EastMoneyKLine> klines) {
        return RecommendationQuality.hasSufficientLiquidity(quote)
                && !RecommendationQuality.isLongSideways(klines);
    }

    private ScoredCycle score(
            CycleSeed seed,
            EastMoneyQuote quote,
            List<EastMoneyKLine> klines,
            EastMoneyFundFlowSnapshot fundFlow,
            CyclePeerValuationSnapshot peerValuation,
            CycleTrialRuleSet ruleSet
    ) {
        TechnicalContext technicalContext = technicalContext(quote, klines);
        CycleTechnicalSnapshot technical = technicalContext.snapshot();
        BigDecimal catalyst = BigDecimal.valueOf(seed.catalystScore());
        BigDecimal priceLocation = priceLocationScore(technical);
        BigDecimal reversal = reversalScore(technicalContext);
        BigDecimal volume = volumeScore(technical, fundFlow);
        BigDecimal valuation = valuationScore(quote, peerValuation);
        BigDecimal finalScore = scale(weighted(catalyst, "0.30")
                .add(weighted(priceLocation, "0.20"))
                .add(weighted(reversal, "0.25"))
                .add(weighted(volume, "0.15"))
                .add(weighted(valuation, "0.10")));
        CycleTrialScoreBreakdown breakdown = new CycleTrialScoreBreakdown(
                scale(catalyst),
                scale(priceLocation),
                scale(reversal),
                scale(volume),
                scale(valuation),
                finalScore
        );

        ActionDecision decision = decide(seed, quote, technicalContext, breakdown, ruleSet);
        BigDecimal latestPrice = latestPrice(quote, technicalContext);
        BigDecimal stopPrice = latestPrice == null ? null : scale(latestPrice.multiply(BigDecimal.ONE.subtract(ruleSet.stopLossPercent().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))));
        BigDecimal buyZoneLow = latestPrice == null ? null : scale(latestPrice.multiply(BigDecimal.ONE.subtract(ruleSet.pullbackZonePercent().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))));
        BigDecimal buyZoneHigh = latestPrice;

        CycleTrialCandidate candidate = new CycleTrialCandidate(
                0,
                seed.symbol(),
                quote == null || quote.name() == null ? seed.name() : quote.name(),
                seed.assetGroup(),
                seed.cycleDriver(),
                quote == null ? null : quote.industry(),
                latestPrice,
                changePercent(quote, technicalContext),
                quote == null ? null : firstPresent(quote.peTtm(), quote.peRatio()),
                quote == null ? null : quote.pbRatio(),
                peerValuation,
                quote == null ? null : quote.amount(),
                decision.phase(),
                decision.phaseLabel(),
                decision.action(),
                decision.actionLabel(),
                decision.reason(),
                todayAdvice(seed, quote, technicalContext, fundFlow, peerValuation, breakdown, decision, ruleSet),
                breakdown,
                technical,
                buyZoneLow,
                buyZoneHigh,
                stopPrice,
                seed.catalysts(),
                seed.risks(),
                entryRules(decision, ruleSet),
                exitRules(ruleSet),
                evidence(seed, quote, technicalContext, fundFlow, peerValuation)
        );
        return new ScoredCycle(candidate);
    }

    private ActionDecision decide(
            CycleSeed seed,
            EastMoneyQuote quote,
            TechnicalContext context,
            CycleTrialScoreBreakdown score,
            CycleTrialRuleSet ruleSet
    ) {
        BigDecimal latestPrice = latestPrice(quote, context);
        if (latestPrice == null || context.snapshot().tradeDate() == null) {
            return new ActionDecision("DATA_REVIEW", "数据复核", "DATA_REVIEW", "数据不足", "缺少实时价格或 K 线，不能给出试仓动作。");
        }

        BigDecimal change = changePercent(quote, context);
        BigDecimal volumeRatio = context.snapshot().volumeRatio20();
        boolean overMa20 = latestPrice.compareTo(nullToZero(context.snapshot().ma20())) > 0;
        boolean volumeBreakout = volumeRatio != null && volumeRatio.compareTo(ruleSet.minVolumeRatioForBreakout()) >= 0;
        boolean strongRise = change != null && change.compareTo(new BigDecimal("3.00")) >= 0;
        boolean chaseRisk = change != null && change.compareTo(ruleSet.maxChaseRisePercent()) >= 0;
        boolean rightStart = overMa20 && volumeBreakout && strongRise;
        boolean nearLow = context.snapshot().rangePosition60() == null
                || context.snapshot().rangePosition60().compareTo(new BigDecimal("45")) <= 0;
        boolean scoreEnoughForTrial = score.finalScore().compareTo(ruleSet.leftTrialScoreThreshold()) >= 0;
        boolean scoreEnoughForRight = score.finalScore().compareTo(ruleSet.rightAddScoreThreshold()) >= 0;

        if (rightStart && chaseRisk) {
            return new ActionDecision(
                    "RIGHT_EARLY",
                    "右侧早期",
                    "RIGHT_START_WAIT_PULLBACK",
                    "右侧启动-等回踩",
                    "已经放量站上关键均线，但单日涨幅超过追涨阈值，新开仓不再舒服。"
            );
        }
        if (seed.catalystScore() <= 50) {
            return new ActionDecision(
                    "EVIDENCE_REVIEW",
                    "证据待补",
                    "EVIDENCE_REVIEW",
                    "周期证据待补",
                    "价格形态可继续观察，但行业价格、库存、产能和供需数据尚未形成可核验的周期催化证据。"
            );
        }
        if (rightStart && scoreEnoughForRight) {
            return new ActionDecision(
                    "RIGHT_CONFIRM",
                    "右侧确认",
                    "RIGHT_ADD",
                    "右侧加仓",
                    "放量站上关键均线且涨幅未触发追高阈值，可以按计划小幅加仓。"
            );
        }
        if (scoreEnoughForTrial && nearLow && (change == null || change.compareTo(ruleSet.maxChaseRisePercent()) < 0)) {
            return new ActionDecision(
                    "LEFT_TRIAL",
                    "左侧试仓",
                    "LEFT_TRIAL",
                    "左侧试仓",
                    "周期催化和低位赔率匹配，但右侧尚未完全确认，只适合小仓试。"
            );
        }
        if (score.finalScore().compareTo(ruleSet.leftTrialScoreThreshold()) >= 0) {
            return new ActionDecision(
                    "WATCH_CONFIRM",
                    "观察确认",
                    "WATCH_CONFIRM",
                    "观察确认",
                    "赔率可以继续看，但位置、量能或突破条件还没有同时满足。"
            );
        }
        return new ActionDecision(
                "LOW_ODDS",
                "赔率不足",
                "AVOID",
                "暂不介入",
                "当前催化、位置和反转信号不足，不适合为了波动入场。"
        );
    }

    private TradingAdvice todayAdvice(
            CycleSeed seed,
            EastMoneyQuote quote,
            TechnicalContext context,
            EastMoneyFundFlowSnapshot fundFlow,
            CyclePeerValuationSnapshot peerValuation,
            CycleTrialScoreBreakdown score,
            ActionDecision decision,
            CycleTrialRuleSet ruleSet
    ) {
        BigDecimal latestPrice = latestPrice(quote, context);
        String priceText = latestPrice == null ? "缺失" : latestPrice.toPlainString();
        String changeText = changePercent(quote, context) == null ? "缺失" : changePercent(quote, context).toPlainString() + "%";
        if ("LEFT_TRIAL".equals(decision.action())) {
            List<String> reasons = new ArrayList<>(List.of(
                    "周期催化分 " + score.catalystScore() + "，价格位置分 " + score.priceLocationScore() + "。",
                    "当前价 " + priceText + "，涨跌幅 " + changeText + "，尚未触发追涨阈值 " + ruleSet.maxChaseRisePercent() + "%。",
                    seed.catalysts().get(0)
            ));
            addFundFlowReason(reasons, fundFlow);
            addPeerValuationReason(reasons, peerValuation);
            return new TradingAdvice(
                    "ADD",
                    "试仓",
                    70,
                    "适合左侧小仓试，不适合重仓确认。",
                    reasons,
                    List.of(
                            "单票只用计划仓位的 10%-20%",
                            "跌破试仓止损价必须退出，不补成重仓",
                            "右侧确认后再考虑第二笔"
                    )
            );
        }
        if ("RIGHT_ADD".equals(decision.action())) {
            List<String> reasons = new ArrayList<>(List.of(
                    "价格站上 20 日线，量能达到突破阈值。",
                    "今日涨幅未超过追涨阈值 " + ruleSet.maxChaseRisePercent() + "%。",
                    "综合分 " + score.finalScore() + " 达到右侧加仓线 " + ruleSet.rightAddScoreThreshold() + "。"
            ));
            addFundFlowReason(reasons, fundFlow);
            addPeerValuationReason(reasons, peerValuation);
            return new TradingAdvice(
                    "ADD",
                    "小幅加仓",
                    76,
                    "右侧启动已出现，但仍按分批而不是一次打满。",
                    reasons,
                    List.of(
                            "只加第二笔，不追第三笔",
                            "放量突破后回落跌回 20 日线，降低仓位",
                            "基本面催化若证伪则退出"
                    )
            );
        }
        if ("RIGHT_START_WAIT_PULLBACK".equals(decision.action())) {
            List<String> reasons = new ArrayList<>(List.of(
                    "当前价 " + priceText + "，涨跌幅 " + changeText + "，超过追涨阈值 " + ruleSet.maxChaseRisePercent() + "%。",
                    "量能放大，说明资金在试图右侧化。",
                    "更好的新开仓位置是突破后回踩不破。"
            ));
            addFundFlowReason(reasons, fundFlow);
            addPeerValuationReason(reasons, peerValuation);
            return new TradingAdvice(
                    "HOLD",
                    "持仓继续/空仓等回踩",
                    72,
                    "右侧启动已经出现，已有仓位可以拿；空仓不适合在急拉后重仓追。",
                    reasons,
                    List.of(
                            "已有底仓不急卖，观察是否封住或强收",
                            "空仓等回踩区间，不在急拉末端重仓追",
                            "次日高开过多且放量滞涨，先兑现一部分"
                    )
            );
        }
        if ("WATCH_CONFIRM".equals(decision.action())) {
            List<String> reasons = new ArrayList<>(List.of(decision.reason(), "综合分 " + score.finalScore() + "。"));
            addFundFlowReason(reasons, fundFlow);
            addPeerValuationReason(reasons, peerValuation);
            return new TradingAdvice(
                    "WAIT",
                    "等待确认",
                    58,
                    "赔率可观察，但还没有形成左侧试仓或右侧加仓条件。",
                    reasons,
                    List.of("等待更近的止损位", "等待量能或价格突破确认")
            );
        }
        List<String> reasons = new ArrayList<>(List.of(decision.reason()));
        addFundFlowReason(reasons, fundFlow);
        addPeerValuationReason(reasons, peerValuation);
        return new TradingAdvice(
                "WAIT",
                "暂不介入",
                50,
                "当前不是舒服的试仓点。",
                reasons,
                List.of("避免为了日内波动追单", "等待催化、价格和量能重新共振")
        );
    }

    private List<String> entryRules(ActionDecision decision, CycleTrialRuleSet ruleSet) {
        if ("LEFT_TRIAL".equals(decision.action())) {
            return List.of(
                    "只能用计划仓位 10%-20% 试仓",
                    "价格接近 20/60 日低位，且止损距离小于 " + ruleSet.stopLossPercent() + "%",
                    "基本面催化必须能解释价格反弹，而不是只看分时拉升",
                    "右侧确认前不追加第二笔"
            );
        }
        if ("RIGHT_ADD".equals(decision.action()) || "RIGHT_START_WAIT_PULLBACK".equals(decision.action())) {
            return List.of(
                    "站上 20 日线并且量能大于 " + ruleSet.minVolumeRatioForBreakout() + " 倍均量",
                    "单日涨幅低于 " + ruleSet.maxChaseRisePercent() + "% 才允许新开第二笔",
                    "若已经急拉，只能等回踩 " + ruleSet.pullbackZonePercent() + "% 左右不破",
                    "突破后 1-2 日不能快速跌回关键位"
            );
        }
        return List.of("等待催化、位置、量能三者共振", "不要把周期观察票当长期核心资产");
    }

    private List<String> exitRules(CycleTrialRuleSet ruleSet) {
        return List.of(
                "试仓后跌破止损价或继续弱于板块，退出而不是补仓",
                "放量冲高回落并跌回 20 日线，降低仓位",
                "周期品价格或公司月度/季度数据证伪，退出",
                "单日急拉后次日高开滞涨，可分批兑现"
        );
    }

    private List<CycleTrialEvidence> evidence(
            CycleSeed seed,
            EastMoneyQuote quote,
            TechnicalContext context,
            EastMoneyFundFlowSnapshot fundFlow,
            CyclePeerValuationSnapshot peerValuation
    ) {
        List<CycleTrialEvidence> evidence = new ArrayList<>();
        evidence.add(new CycleTrialEvidence(
                seed.assetGroup() + "催化",
                String.join("；", seed.catalysts()),
                null,
                seed.catalystScore()
        ));
        if (quote != null) {
            evidence.add(new CycleTrialEvidence(
                    quote.sourceName() == null ? "实时行情" : quote.sourceName(),
                    "用于核验最新价、涨跌幅、PE、PB 和成交额。",
                    quote.quoteUrl(),
                    82
            ));
        }
        if (context.snapshot().tradeDate() != null) {
            evidence.add(new CycleTrialEvidence(
                    "近 60 日 K 线",
                    "用于判断低位、均线、放量和突破确认。",
                    quote == null ? null : quote.quoteUrl(),
                    78
            ));
        }
        if (peerValuation != null && peerValuation.peers() != null && peerValuation.peers().size() >= MIN_PEER_VALUATION_COUNT) {
            evidence.add(new CycleTrialEvidence(
                    "行业头部 PE/PB 对比",
                    peerValuationSummary(peerValuation),
                    quote == null ? null : quote.quoteUrl(),
                    peerValuation.valuationAdvantage() ? 84 : 68
            ));
        }
        if (fundFlow != null) {
            evidence.add(new CycleTrialEvidence(
                    "主力资金流",
                    fundFlowSummary(fundFlow),
                    fundFlow.sourceUrl(),
                    76
            ));
        } else {
            evidence.add(new CycleTrialEvidence(
                    "主力资金流",
                    "资金流接口超时、盘前为空或未进入当前展示 Top N 的资金流补证范围；本次未纳入资金评分。",
                    quote == null ? null : quote.quoteUrl(),
                    45
            ));
        }
        return evidence;
    }

    private TechnicalContext technicalContext(EastMoneyQuote quote, List<EastMoneyKLine> klines) {
        if (klines == null || klines.isEmpty()) {
            return new TechnicalContext(new CycleTechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null), null, null, false);
        }
        List<EastMoneyKLine> sorted = klines.stream()
                .filter(kline -> kline.close() != null)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (sorted.isEmpty()) {
            return new TechnicalContext(new CycleTechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null), null, null, false);
        }
        EastMoneyKLine last = sorted.get(sorted.size() - 1);
        EastMoneyKLine previous = sorted.size() >= 2 ? sorted.get(sorted.size() - 2) : null;
        BigDecimal price = quote == null || quote.latestPrice() == null ? last.close() : quote.latestPrice();
        BigDecimal ma5 = movingAverage(sorted, 5);
        BigDecimal ma10 = movingAverage(sorted, 10);
        BigDecimal ma20 = movingAverage(sorted, 20);
        BigDecimal ma60 = movingAverage(sorted, 60);
        List<EastMoneyKLine> previousRows = sorted.size() >= 2 ? sorted.subList(0, sorted.size() - 1) : sorted;
        BigDecimal previousHigh20 = high(previousRows, 20);
        BigDecimal previousHigh60 = high(previousRows, 60);
        BigDecimal low20 = low(sorted, 20);
        BigDecimal low60 = low(sorted, 60);
        BigDecimal volumeRatio5 = volumeRatio(sorted, 5);
        BigDecimal volumeRatio20 = volumeRatio(sorted, 20);
        BigDecimal rangePosition60 = rangePosition(price, low60, high(sorted, 60));
        BigDecimal closeNearHigh = ratio(price.subtract(nullToZero(last.low())), nullToZero(last.high()).subtract(nullToZero(last.low())));
        BigDecimal reboundFrom20Low = percent(price.subtract(nullToZero(low20)), low20);
        BigDecimal distanceToMa20 = percent(price.subtract(nullToZero(ma20)), ma20);

        CycleTechnicalSnapshot snapshot = new CycleTechnicalSnapshot(
                last.tradeDate().toString(),
                scale(ma5),
                scale(ma10),
                scale(ma20),
                scale(ma60),
                scale(previousHigh20),
                scale(previousHigh60),
                scale(low20),
                scale(low60),
                scale(volumeRatio5),
                scale(volumeRatio20),
                scale(rangePosition60),
                scale(closeNearHigh == null ? null : closeNearHigh.multiply(new BigDecimal("100"))),
                scale(reboundFrom20Low),
                scale(distanceToMa20)
        );
        boolean higherThanPrevious = previous != null && price.compareTo(previous.close()) > 0;
        return new TechnicalContext(snapshot, last, previous, higherThanPrevious);
    }

    private BigDecimal priceLocationScore(CycleTechnicalSnapshot snapshot) {
        if (snapshot.rangePosition60() == null) {
            return new BigDecimal("50");
        }
        return clamp(new BigDecimal("100").subtract(snapshot.rangePosition60().multiply(new BigDecimal("0.75"))));
    }

    private BigDecimal reversalScore(TechnicalContext context) {
        CycleTechnicalSnapshot snapshot = context.snapshot();
        if (snapshot.tradeDate() == null) {
            return new BigDecimal("40");
        }
        BigDecimal price = context.last() == null ? null : context.last().close();
        BigDecimal score = new BigDecimal("35");
        if (price != null && snapshot.ma5() != null && price.compareTo(snapshot.ma5()) > 0) {
            score = score.add(new BigDecimal("10"));
        }
        if (price != null && snapshot.ma10() != null && price.compareTo(snapshot.ma10()) > 0) {
            score = score.add(new BigDecimal("10"));
        }
        if (price != null && snapshot.ma20() != null && price.compareTo(snapshot.ma20()) > 0) {
            score = score.add(new BigDecimal("18"));
        }
        if (price != null && snapshot.ma60() != null && price.compareTo(snapshot.ma60()) > 0) {
            score = score.add(new BigDecimal("8"));
        }
        if (context.higherThanPrevious()) {
            score = score.add(new BigDecimal("8"));
        }
        if (snapshot.closeNearHigh() != null && snapshot.closeNearHigh().compareTo(new BigDecimal("70")) >= 0) {
            score = score.add(new BigDecimal("9"));
        }
        if (snapshot.distanceToMa20Percent() != null && snapshot.distanceToMa20Percent().compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(new BigDecimal("7"));
        }
        return clamp(score);
    }

    private BigDecimal volumeScore(CycleTechnicalSnapshot snapshot, EastMoneyFundFlowSnapshot fundFlow) {
        BigDecimal technicalVolumeScore = snapshot.volumeRatio20() == null
                ? null
                : clamp(snapshot.volumeRatio20().multiply(new BigDecimal("45")));
        BigDecimal capitalFlowScore = fundFlowScore(fundFlow);
        if (technicalVolumeScore == null && capitalFlowScore == null) {
            return new BigDecimal("45");
        }
        if (technicalVolumeScore == null) {
            return capitalFlowScore;
        }
        if (capitalFlowScore == null) {
            return technicalVolumeScore;
        }
        return clamp(weighted(technicalVolumeScore, "0.70").add(weighted(capitalFlowScore, "0.30")));
    }

    private BigDecimal fundFlowScore(EastMoneyFundFlowSnapshot fundFlow) {
        if (fundFlow == null || fundFlow.mainNetInflow() == null) {
            return null;
        }
        BigDecimal score = new BigDecimal("50");
        score = score.add(isPositive(fundFlow.mainNetInflow()) ? new BigDecimal("18") : new BigDecimal("-18"));
        if (isPositive(fundFlow.superLargeNetInflow())) {
            score = score.add(new BigDecimal("12"));
        } else if (isNegative(fundFlow.superLargeNetInflow())) {
            score = score.add(new BigDecimal("-6"));
        }
        if (isPositive(fundFlow.largeNetInflow())) {
            score = score.add(new BigDecimal("8"));
        } else if (isNegative(fundFlow.largeNetInflow())) {
            score = score.add(new BigDecimal("-5"));
        }
        if (isNegative(fundFlow.smallNetInflow()) && isPositive(fundFlow.largeOrderNetInflow())) {
            score = score.add(new BigDecimal("8"));
        }
        if (fundFlow.mainNetInflowRatio() != null) {
            if (fundFlow.mainNetInflowRatio().compareTo(new BigDecimal("8")) >= 0) {
                score = score.add(new BigDecimal("14"));
            } else if (fundFlow.mainNetInflowRatio().compareTo(new BigDecimal("3")) >= 0) {
                score = score.add(new BigDecimal("8"));
            } else if (fundFlow.mainNetInflowRatio().compareTo(new BigDecimal("-5")) <= 0) {
                score = score.add(new BigDecimal("-16"));
            }
        }
        return clamp(score);
    }

    private BigDecimal valuationScore(EastMoneyQuote quote, CyclePeerValuationSnapshot peerValuation) {
        if (quote == null) {
            return new BigDecimal("45");
        }
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        BigDecimal score = new BigDecimal("25");
        if (pe != null && pe.compareTo(BigDecimal.ZERO) > 0) {
            if (pe.compareTo(new BigDecimal("12")) <= 0) {
                score = score.add(new BigDecimal("42"));
            } else if (pe.compareTo(new BigDecimal("22")) <= 0) {
                score = score.add(new BigDecimal("30"));
            } else if (pe.compareTo(new BigDecimal("40")) <= 0) {
                score = score.add(new BigDecimal("16"));
            } else {
                score = score.add(new BigDecimal("5"));
            }
        }
        if (pb != null && pb.compareTo(BigDecimal.ZERO) > 0) {
            if (pb.compareTo(new BigDecimal("1.5")) <= 0) {
                score = score.add(new BigDecimal("33"));
            } else if (pb.compareTo(new BigDecimal("3")) <= 0) {
                score = score.add(new BigDecimal("22"));
            } else {
                score = score.add(new BigDecimal("8"));
            }
        }
        if (peerValuation != null) {
            if (peerValuation.valuationAdvantage()) {
                score = score.add(new BigDecimal("12"));
            }
        }
        return clamp(score);
    }

    private Map<String, CyclePeerValuationSnapshot> peerValuations(List<CycleSeed> seeds, Map<String, EastMoneyQuote> quotes) {
        Map<String, CyclePeerValuationSnapshot> snapshots = new LinkedHashMap<>();
        Map<String, CycleSeed> seedsBySymbol = seeds.stream()
                .collect(Collectors.toMap(CycleSeed::symbol, Function.identity(), (left, right) -> left));
        List<String> industries = seeds.stream()
                .map(seed -> peerIndustryName(seed, quotes.get(seed.symbol())))
                .filter(industry -> industry != null && !industry.isBlank())
                .distinct()
                .toList();
        Map<String, PeerSample> peerSamplesByIndustry = fetchPeerSamplesByIndustry(industries);
        for (CycleSeed seed : seeds) {
            EastMoneyQuote quote = quotes.get(seed.symbol());
            String industry = peerIndustryName(seed, quote);
            if (industry == null || industry.isBlank()) {
                snapshots.put(seed.symbol(), emptyPeerValuation(null, "缺少行业分类，无法做同业头部估值对比。"));
                continue;
            }
            PeerSample peerSample = peerSamplesByIndustry.getOrDefault(industry, new PeerSample(List.of(), false));
            List<EastMoneyQuote> peers = peerSample.quotes();
            if (peers.size() < MIN_PEER_VALUATION_COUNT) {
                peers = mergePeerQuotes(peers, fallbackPeerQuotes(seed, industry, quotes, seedsBySymbol));
            }
            snapshots.put(seed.symbol(), buildPeerValuation(seed, quote, industry, peers, peerSample.fromIndustryBoard()));
        }
        return snapshots;
    }

    private Map<String, PeerSample> fetchPeerSamplesByIndustry(List<String> industries) {
        Map<String, CompletableFuture<List<EastMoneyQuote>>> futures = new LinkedHashMap<>();
        for (String industry : industries) {
            futures.put(industry, CompletableFuture.supplyAsync(() -> fetchPeerQuotesDirect(industry)));
        }
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]));
        try {
            allFutures.get(PEER_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            logger.warn("周期试仓池同业估值样本批量获取超时，本次未完成行业使用兜底样本。");
        } catch (Exception exception) {
            logger.warn("周期试仓池同业估值样本批量获取失败：{}", exception.getMessage());
        }
        Map<String, PeerSample> samples = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<List<EastMoneyQuote>>> entry : futures.entrySet()) {
            CompletableFuture<List<EastMoneyQuote>> future = entry.getValue();
            if (!future.isDone()) {
                future.cancel(true);
                logger.warn("周期试仓池同业估值样本获取超时：{}，本次只使用兜底样本。", entry.getKey());
                samples.put(entry.getKey(), new PeerSample(List.of(), false));
                continue;
            }
            try {
                List<EastMoneyQuote> peers = future.getNow(List.of());
                samples.put(entry.getKey(), new PeerSample(peers, peers.size() >= MIN_PEER_VALUATION_COUNT));
            } catch (RuntimeException exception) {
                logger.warn("周期试仓池同业估值样本获取失败：{}，原因：{}", entry.getKey(), exception.getMessage());
                samples.put(entry.getKey(), new PeerSample(List.of(), false));
            }
        }
        return samples;
    }

    private List<EastMoneyQuote> fetchPeerQuotesDirect(String industry) {
        try {
            return eastMoneyClient.fetchIndustryBoardConstituents(industry, PEER_BOARD_FETCH_LIMIT).stream()
                    .filter(this::hasUsableValuation)
                    .sorted(Comparator.comparing((EastMoneyQuote quote) -> nullToZero(quote.amount())).reversed())
                    .limit(PEER_DISPLAY_LIMIT + 1L)
                    .toList();
        } catch (RuntimeException exception) {
            logger.warn("周期试仓池同业估值样本获取失败：{}，原因：{}", industry, exception.getMessage());
            return List.of();
        }
    }

    private List<EastMoneyQuote> fallbackPeerQuotes(
            CycleSeed seed,
            String industry,
            Map<String, EastMoneyQuote> quotes,
            Map<String, CycleSeed> seedsBySymbol
    ) {
        return quotes.values().stream()
                .filter(quote -> quote != null && !seed.symbol().equals(quote.symbol()))
                .filter(this::hasUsableValuation)
                .filter(quote -> {
                    CycleSeed peerSeed = seedsBySymbol.get(quote.symbol());
                    String peerIndustry = peerSeed == null ? quote.industry() : peerIndustryName(peerSeed, quote);
                    if (peerSeed != null) {
                        return sameText(industry, peerIndustry);
                    }
                    return sameText(industry, peerIndustry) || sameText(industry, quote.industry()) || sameText(industry, mappedIndustryByQuoteIndustry(quote.industry()));
                })
                .sorted(Comparator.comparing((EastMoneyQuote quote) -> nullToZero(quote.amount())).reversed())
                .limit(PEER_DISPLAY_LIMIT)
                .toList();
    }

    private List<EastMoneyQuote> mergePeerQuotes(List<EastMoneyQuote> primary, List<EastMoneyQuote> fallback) {
        Map<String, EastMoneyQuote> merged = new LinkedHashMap<>();
        primary.forEach(quote -> merged.put(quote.symbol(), quote));
        fallback.forEach(quote -> merged.putIfAbsent(quote.symbol(), quote));
        return merged.values().stream()
                .sorted(Comparator.comparing((EastMoneyQuote quote) -> nullToZero(quote.amount())).reversed())
                .limit(PEER_DISPLAY_LIMIT + 1L)
                .toList();
    }

    private CyclePeerValuationSnapshot buildPeerValuation(
            CycleSeed seed,
            EastMoneyQuote quote,
            String industry,
            List<EastMoneyQuote> peerQuotes,
            boolean reliablePeerSample
    ) {
        List<CyclePeerValuationCompany> peers = peerQuotes.stream()
                .filter(peer -> !seed.symbol().equals(peer.symbol()))
                .filter(this::hasUsableValuation)
                .sorted(Comparator.comparing((EastMoneyQuote peer) -> nullToZero(peer.amount())).reversed())
                .limit(PEER_DISPLAY_LIMIT)
                .map(this::toPeerCompany)
                .toList();
        if (quote == null) {
            return new CyclePeerValuationSnapshot(industry, null, null, null, null, false, "候选股行情缺失，无法做同业估值对比。", peers);
        }
        BigDecimal candidatePe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal candidatePb = quote.pbRatio();
        BigDecimal averagePe = average(peers.stream().map(CyclePeerValuationCompany::peTtm).toList());
        BigDecimal averagePb = average(peers.stream().map(CyclePeerValuationCompany::pbRatio).toList());
        BigDecimal peDiscount = discountPercent(candidatePe, averagePe);
        BigDecimal pbDiscount = discountPercent(candidatePb, averagePb);
        boolean enoughPeers = reliablePeerSample && peers.size() >= MIN_PEER_VALUATION_COUNT;
        boolean advantage = enoughPeers
                && peDiscount != null
                && pbDiscount != null
                && peDiscount.compareTo(SIGNIFICANT_DISCOUNT_PERCENT) >= 0
                && pbDiscount.compareTo(SIGNIFICANT_DISCOUNT_PERCENT) >= 0;
        String conclusion = peerValuationConclusion(reliablePeerSample, peers.size() >= MIN_PEER_VALUATION_COUNT, candidatePe, candidatePb, peDiscount, pbDiscount, advantage);
        return new CyclePeerValuationSnapshot(
                industry,
                scale(averagePe),
                scale(averagePb),
                scale(peDiscount),
                scale(pbDiscount),
                advantage,
                conclusion,
                peers
        );
    }

    private CyclePeerValuationSnapshot emptyPeerValuation(String industry, String conclusion) {
        return new CyclePeerValuationSnapshot(industry, null, null, null, null, false, conclusion, List.of());
    }

    private CyclePeerValuationCompany toPeerCompany(EastMoneyQuote quote) {
        return new CyclePeerValuationCompany(
                quote.symbol(),
                quote.name(),
                quote.industry(),
                scale(firstPresent(quote.peTtm(), quote.peRatio())),
                scale(quote.pbRatio()),
                quote.amount(),
                quote.quoteUrl()
        );
    }

    private String peerIndustryName(CycleSeed seed, EastMoneyQuote quote) {
        String mapped = mappedIndustry(seed.assetGroup());
        String industry = quote == null ? null : quote.industry();
        if (industry == null || industry.isBlank()) {
            return mapped;
        }
        String mappedByQuote = mappedIndustryByQuoteIndustry(industry);
        if (mappedByQuote != null) {
            return mapped == null ? mappedByQuote : mapped;
        }
        return industry;
    }

    private String mappedIndustry(String assetGroup) {
        if (assetGroup == null) {
            return null;
        }
        if (assetGroup.contains("食用菌") || assetGroup.contains("生猪") || assetGroup.contains("养殖") || assetGroup.contains("禽链")) {
            return "农牧饲渔";
        }
        if (assetGroup.contains("化工")) {
            return "化学制品";
        }
        if (assetGroup.contains("火电")) {
            return "电力行业";
        }
        if (assetGroup.contains("油气")) {
            return "石油行业";
        }
        if (assetGroup.contains("煤炭")) {
            return "煤炭行业";
        }
        return assetGroup;
    }

    private String mappedIndustryByQuoteIndustry(String industry) {
        if (industry == null) {
            return null;
        }
        if ("农业".equals(industry) || "养殖业".equals(industry) || "农林牧渔".equals(industry)) {
            return "农牧饲渔";
        }
        return null;
    }

    private boolean hasUsableValuation(EastMoneyQuote quote) {
        BigDecimal pe = quote == null ? null : firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote == null ? null : quote.pbRatio();
        return pe != null
                && pb != null
                && pe.compareTo(BigDecimal.ZERO) > 0
                && pb.compareTo(BigDecimal.ZERO) > 0
                && pe.compareTo(new BigDecimal("120")) <= 0
                && pb.compareTo(new BigDecimal("20")) <= 0;
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> usable = values.stream()
                .filter(value -> value != null && value.compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (usable.isEmpty()) {
            return null;
        }
        BigDecimal sum = usable.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(usable.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal discountPercent(BigDecimal candidateValue, BigDecimal averageValue) {
        if (candidateValue == null || averageValue == null || candidateValue.compareTo(BigDecimal.ZERO) <= 0 || averageValue.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return percent(averageValue.subtract(candidateValue), averageValue);
    }

    private String peerValuationConclusion(
            boolean reliablePeerSample,
            boolean enoughPeers,
            BigDecimal candidatePe,
            BigDecimal candidatePb,
            BigDecimal peDiscount,
            BigDecimal pbDiscount,
            boolean advantage
    ) {
        if (candidatePe == null || candidatePb == null || candidatePe.compareTo(BigDecimal.ZERO) <= 0 || candidatePb.compareTo(BigDecimal.ZERO) <= 0) {
            return "候选股 PE/PB 缺失或为负，本次不做同业估值优势判断。";
        }
        if (!enoughPeers) {
            return "同业有效样本不足 3 家，只展示可取到的 PE/PB，不标记估值优势。";
        }
        if (!reliablePeerSample) {
            return "行业板块源未取到足够头部样本，当前只展示同周期池兜底样本，不标记估值优势。";
        }
        if (advantage) {
            return "PE 和 PB 均较行业头部均值折价 20% 以上，标记为同业估值优势。";
        }
        if (nonNegative(peDiscount) && nonNegative(pbDiscount)) {
            return "PE/PB 低于行业头部均值，但折价幅度未达到显著优势阈值。";
        }
        return "PE 或 PB 未明显优于行业头部均值，估值优势不成立。";
    }

    private String peerValuationSummary(CyclePeerValuationSnapshot peerValuation) {
        String peerNames = peerValuation.peers().stream()
                .limit(PEER_DISPLAY_LIMIT)
                .map(peer -> peer.name() + "(" + peer.symbol() + ")")
                .collect(Collectors.joining("、"));
        return "行业：" + peerValuation.industry()
                + "；头部均值 PE " + formatDecimal(peerValuation.averagePeTtm())
                + "，PB " + formatDecimal(peerValuation.averagePbRatio())
                + "；候选股 PE 折价 " + formatPercent(peerValuation.candidatePeDiscountPercent())
                + "，PB 折价 " + formatPercent(peerValuation.candidatePbDiscountPercent())
                + "；对比样本：" + peerNames
                + "；结论：" + peerValuation.conclusion();
    }

    private List<EastMoneyQuote> fetchQuotes(List<String> symbols) {
        List<EastMoneyQuote> primaryQuotes = List.of();
        try {
            primaryQuotes = eastMoneyClient.fetchEastMoneyQuotesBySymbols(symbols, symbols.size());
        } catch (RuntimeException exception) {
            logger.warn("周期试仓池东方财富行情失败，尝试腾讯兜底：{}", exception.getMessage());
        }
        if (primaryQuotes.size() >= symbols.size()) {
            return primaryQuotes;
        }
        List<String> missingSymbols = missingSymbols(symbols, primaryQuotes);
        List<EastMoneyQuote> fallbackQuotes = List.of();
        try {
            fallbackQuotes = eastMoneyClient.fetchTencentQuotes(
                    missingSymbols.isEmpty() ? symbols : missingSymbols,
                    missingSymbols.isEmpty() ? symbols.size() : missingSymbols.size()
            );
        } catch (RuntimeException exception) {
            logger.warn("周期试仓池腾讯行情兜底失败：{}", exception.getMessage());
        }
        return mergeQuotes(primaryQuotes, fallbackQuotes);
    }

    private List<EastMoneyKLine> fetchKLines(String symbol) {
        try {
            LocalDate end = LocalDate.now();
            return eastMoneyClient.fetchDailyKLines(symbol, end.minusDays(180), end);
        } catch (RuntimeException exception) {
            logger.warn("周期试仓池 K 线获取失败：{}，原因：{}", symbol, exception.getMessage());
            return List.of();
        }
    }

    private Map<String, EastMoneyFundFlowSnapshot> fetchFundFlows(List<String> symbols) {
        Map<String, EastMoneyFundFlowSnapshot> fundFlows = new LinkedHashMap<>();
        for (String symbol : symbols) {
            try {
                fetchFundFlowWithDeadline(symbol)
                        .ifPresent(fundFlow -> fundFlows.put(symbol, fundFlow));
            } catch (RuntimeException exception) {
                logger.warn("周期试仓池资金流获取失败：{}，原因：{}", symbol, exception.getMessage());
            }
        }
        return fundFlows;
    }

    private Optional<EastMoneyFundFlowSnapshot> fetchFundFlowWithDeadline(String symbol) {
        CompletableFuture<Optional<EastMoneyFundFlowSnapshot>> future = CompletableFuture.supplyAsync(
                () -> eastMoneyClient.fetchFundFlowSnapshot(symbol)
        );
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            logger.warn("周期试仓池资金流获取超时：{}，本次跳过资金评分。", symbol);
            return Optional.empty();
        } catch (Exception exception) {
            future.cancel(true);
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private List<String> missingSymbols(List<String> symbols, List<EastMoneyQuote> quotes) {
        List<String> fetchedSymbols = quotes.stream().map(EastMoneyQuote::symbol).toList();
        return symbols.stream()
                .filter(symbol -> !fetchedSymbols.contains(symbol))
                .toList();
    }

    private List<EastMoneyQuote> mergeQuotes(List<EastMoneyQuote> primaryQuotes, List<EastMoneyQuote> fallbackQuotes) {
        Map<String, EastMoneyQuote> merged = new LinkedHashMap<>();
        primaryQuotes.forEach(quote -> merged.put(quote.symbol(), quote));
        fallbackQuotes.forEach(quote -> merged.putIfAbsent(quote.symbol(), quote));
        return new ArrayList<>(merged.values());
    }

    private CycleTrialRuleSet resolveRuleSet(
            BigDecimal leftTrialScore,
            BigDecimal rightAddScore,
            BigDecimal maxChaseRise,
            BigDecimal minVolumeRatio
    ) {
        return new CycleTrialRuleSet(
                positiveOrDefault(leftTrialScore, DEFAULT_RULE_SET.leftTrialScoreThreshold()),
                positiveOrDefault(rightAddScore, DEFAULT_RULE_SET.rightAddScoreThreshold()),
                positiveOrDefault(maxChaseRise, DEFAULT_RULE_SET.maxChaseRisePercent()),
                positiveOrDefault(minVolumeRatio, DEFAULT_RULE_SET.minVolumeRatioForBreakout()),
                DEFAULT_RULE_SET.stopLossPercent(),
                DEFAULT_RULE_SET.pullbackZonePercent()
        );
    }

    private CycleTrialCandidate rerank(CycleTrialCandidate candidate, int rank) {
        return new CycleTrialCandidate(
                rank,
                candidate.symbol(),
                candidate.name(),
                candidate.assetGroup(),
                candidate.cycleDriver(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.changePercent(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.peerValuation(),
                candidate.amount(),
                candidate.phase(),
                candidate.phaseLabel(),
                candidate.action(),
                candidate.actionLabel(),
                candidate.reason(),
                candidate.todayAdvice(),
                candidate.score(),
                candidate.technical(),
                candidate.trialBuyZoneLow(),
                candidate.trialBuyZoneHigh(),
                candidate.stopPrice(),
                candidate.catalysts(),
                candidate.risks(),
                candidate.entryRules(),
                candidate.exitRules(),
                candidate.evidence()
        );
    }

    private int actionPriority(String action) {
        return switch (action) {
            case "RIGHT_ADD" -> 5;
            case "RIGHT_START_WAIT_PULLBACK" -> 4;
            case "LEFT_TRIAL" -> 4;
            case "EVIDENCE_REVIEW" -> 2;
            case "WATCH_CONFIRM" -> 2;
            case "DATA_REVIEW" -> 1;
            default -> 0;
        };
    }

    private BigDecimal latestPrice(EastMoneyQuote quote, TechnicalContext context) {
        if (quote != null && quote.latestPrice() != null) {
            return quote.latestPrice();
        }
        return context.last() == null ? null : context.last().close();
    }

    private BigDecimal changePercent(EastMoneyQuote quote, TechnicalContext context) {
        if (quote != null && quote.changePercent() != null) {
            return quote.changePercent();
        }
        if (context.last() == null || context.previous() == null) {
            return null;
        }
        return percent(context.last().close().subtract(context.previous().close()), context.previous().close());
    }

    private BigDecimal movingAverage(List<EastMoneyKLine> rows, int window) {
        List<EastMoneyKLine> slice = lastRows(rows, window);
        if (slice.isEmpty()) {
            return null;
        }
        BigDecimal sum = slice.stream()
                .map(EastMoneyKLine::close)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(slice.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal volumeRatio(List<EastMoneyKLine> rows, int window) {
        if (rows.size() < 2) {
            return null;
        }
        EastMoneyKLine last = rows.get(rows.size() - 1);
        if (last.volume() == null) {
            return null;
        }
        List<EastMoneyKLine> previousRows = rows.subList(0, rows.size() - 1);
        List<EastMoneyKLine> slice = lastRows(previousRows, window);
        if (slice.isEmpty()) {
            return null;
        }
        BigDecimal avg = slice.stream()
                .map(EastMoneyKLine::volume)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(slice.size()), 6, RoundingMode.HALF_UP);
        if (avg.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return last.volume().divide(avg, 6, RoundingMode.HALF_UP);
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
        if (rows.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, rows.size() - window);
        return rows.subList(start, rows.size());
    }

    private BigDecimal rangePosition(BigDecimal price, BigDecimal low, BigDecimal high) {
        if (price == null || low == null || high == null || high.compareTo(low) <= 0) {
            return null;
        }
        return price.subtract(low)
                .divide(high.subtract(low), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal weighted(BigDecimal value, String weight) {
        return value.multiply(new BigDecimal(weight));
    }

    private void addFundFlowReason(List<String> reasons, EastMoneyFundFlowSnapshot fundFlow) {
        if (fundFlow == null) {
            return;
        }
        reasons.add("资金流：" + fundFlowBias(fundFlow) + "，" + fundFlowSummary(fundFlow));
    }

    private void addPeerValuationReason(List<String> reasons, CyclePeerValuationSnapshot peerValuation) {
        if (peerValuation == null || peerValuation.peers() == null || peerValuation.peers().size() < MIN_PEER_VALUATION_COUNT) {
            return;
        }
        reasons.add("同业估值：" + peerValuation.conclusion()
                + " PE 折价 " + formatPercent(peerValuation.candidatePeDiscountPercent())
                + "，PB 折价 " + formatPercent(peerValuation.candidatePbDiscountPercent()) + "。");
    }

    private String fundFlowSummary(EastMoneyFundFlowSnapshot fundFlow) {
        return "主力 " + formatWan(fundFlow.mainNetInflow())
                + "，超大单 " + formatWan(fundFlow.superLargeNetInflow())
                + "，大单 " + formatWan(fundFlow.largeNetInflow())
                + "，小单 " + formatWan(fundFlow.smallNetInflow())
                + "，主力净占比 " + formatPercent(fundFlow.mainNetInflowRatio()) + "。";
    }

    private String fundFlowBias(EastMoneyFundFlowSnapshot fundFlow) {
        if (isPositive(fundFlow.mainNetInflow()) && isPositive(fundFlow.largeOrderNetInflow()) && isNegative(fundFlow.smallNetInflow())) {
            return "主力/大单净流入且小单净流出，偏主动做多";
        }
        if (isPositive(fundFlow.mainNetInflow())) {
            return "主力净流入，资金面偏积极";
        }
        if (isNegative(fundFlow.mainNetInflow()) && isPositive(fundFlow.smallNetInflow())) {
            return "主力净流出、小单承接，资金面偏弱";
        }
        if (isNegative(fundFlow.mainNetInflow())) {
            return "主力净流出，需要降低追涨冲动";
        }
        return "资金结构中性";
    }

    private String formatWan(BigDecimal yuan) {
        if (yuan == null) {
            return "缺失";
        }
        BigDecimal value = yuan.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP).stripTrailingZeros();
        return signed(value) + "万";
    }

    private String formatPercent(BigDecimal value) {
        if (value == null) {
            return "缺失";
        }
        return signed(scale(value)) + "%";
    }

    private String formatDecimal(BigDecimal value) {
        return value == null ? "缺失" : scale(value).toPlainString();
    }

    private String signed(BigDecimal value) {
        if (value == null) {
            return "缺失";
        }
        return (value.compareTo(BigDecimal.ZERO) > 0 ? "+" : "") + value.toPlainString();
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    private boolean nonNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private boolean sameText(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
    }

    private BigDecimal firstPresent(BigDecimal left, BigDecimal right) {
        return left == null ? right : left;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(new BigDecimal("100")) > 0) {
            return new BigDecimal("100");
        }
        return scale(value);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private record CycleSeed(
            String symbol,
            String name,
            String assetGroup,
            String cycleDriver,
            int catalystScore,
            List<String> catalysts,
            List<String> risks
    ) {
    }

    private record TechnicalContext(
            CycleTechnicalSnapshot snapshot,
            EastMoneyKLine last,
            EastMoneyKLine previous,
            boolean higherThanPrevious
    ) {
    }

    private record ActionDecision(
            String phase,
            String phaseLabel,
            String action,
            String actionLabel,
            String reason
    ) {
    }

    private record ScoredCycle(CycleTrialCandidate candidate) {
    }

    private record ScoredSeed(CycleSeed seed, ScoredCycle scoredCycle) {
        private CycleTrialCandidate candidate() {
            return scoredCycle.candidate();
        }
    }

    private record PeerSample(
            List<EastMoneyQuote> quotes,
            boolean fromIndustryBoard
    ) {
    }
}

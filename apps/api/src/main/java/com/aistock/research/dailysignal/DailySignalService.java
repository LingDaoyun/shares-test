package com.aistock.research.dailysignal;

import com.aistock.research.mispricing.MispricedAsset;
import com.aistock.research.mispricing.MispricingEvidenceItem;
import com.aistock.research.mispricing.MispricingReport;
import com.aistock.research.tech.TechEvidenceItem;
import com.aistock.research.tech.TechTrackedStock;
import com.aistock.research.tech.TechTrackingReport;
import com.aistock.research.tech.TechTrackingService;
import com.aistock.research.mispricing.MispricingService;
import com.aistock.research.tradefeedback.RecommendationSource;
import com.aistock.research.tradefeedback.StrategyFeedbackService;
import com.aistock.research.tradefeedback.StrategyFeedbackSummary;
import com.aistock.research.trading.StrategyDecisionBroker;
import com.aistock.research.trading.StrategyDecisionInput;
import com.aistock.research.trading.TradingAdvice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DailySignalService {

    private static final int DEFAULT_LIMIT = 18;
    private static final int DEFAULT_TECH_LIMIT = 10;
    private static final int DEFAULT_MISPRICING_LIMIT = 10;
    private static final BigDecimal DEFAULT_HOT_HEAT = new BigDecimal("82");
    private static final String SOURCE_PROJECT = "ZhuLinsen/daily_stock_analysis";
    private static final String SOURCE_COMMIT = "48b9e18a";

    private final TechTrackingService techTrackingService;
    private final MispricingService mispricingService;
    private final StrategyDecisionBroker strategyDecisionBroker;
    private final StrategyFeedbackService strategyFeedbackService;

    public DailySignalService(TechTrackingService techTrackingService, MispricingService mispricingService) {
        this(techTrackingService, mispricingService, new StrategyDecisionBroker(), null);
    }

    public DailySignalService(
            TechTrackingService techTrackingService,
            MispricingService mispricingService,
            StrategyDecisionBroker strategyDecisionBroker
    ) {
        this(techTrackingService, mispricingService, strategyDecisionBroker, null);
    }

    @Autowired
    public DailySignalService(
            TechTrackingService techTrackingService,
            MispricingService mispricingService,
            StrategyDecisionBroker strategyDecisionBroker,
            StrategyFeedbackService strategyFeedbackService
    ) {
        this.techTrackingService = techTrackingService;
        this.mispricingService = mispricingService;
        this.strategyDecisionBroker = strategyDecisionBroker;
        this.strategyFeedbackService = strategyFeedbackService;
    }

    public DailySignalReport report(Integer limit, Integer techLimit, Integer mispricingLimit, BigDecimal hotHeat) {
        int safeTechLimit = positive(techLimit, DEFAULT_TECH_LIMIT);
        int safeMispricingLimit = positive(mispricingLimit, DEFAULT_MISPRICING_LIMIT);
        int safeLimit = positive(limit, DEFAULT_LIMIT);
        BigDecimal resolvedHotHeat = positive(hotHeat, DEFAULT_HOT_HEAT);

        TechTrackingReport techReport = techTrackingService.report(safeTechLimit, null, null, null, null);
        MispricingReport mispricingReport = mispricingService.report(safeMispricingLimit, resolvedHotHeat, null, null, null);
        List<StrategyFeedbackSummary> feedbackSummaries = feedbackSummariesSafely();

        List<DailyDecisionSignal> rawSignals = new ArrayList<>();
        techReport.candidates().forEach(stock -> rawSignals.add(fromTech(stock)));
        mispricingReport.candidates().forEach(asset -> rawSignals.add(fromMispricing(asset)));

        List<DailyDecisionSignal> brokeredSignals = consolidateDuplicateSymbols(rawSignals, feedbackSummaries).stream()
                .map(signal -> applyStrategyFeedback(signal, feedbackSummaries))
                .toList();
        List<DailyDecisionSignal> ranked = brokeredSignals.stream()
                .sorted(Comparator.comparingInt((DailyDecisionSignal signal) -> actionPriority(signal.action())).reversed()
                        .thenComparing(DailyDecisionSignal::confidence, Comparator.reverseOrder())
                        .thenComparing(DailyDecisionSignal::score, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .toList();
        List<DailyDecisionSignal> signals = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            signals.add(rerank(ranked.get(index), index + 1));
        }

        Map<String, Long> actionCounts = signals.stream()
                .collect(Collectors.groupingBy(DailyDecisionSignal::action, LinkedHashMap::new, Collectors.counting()));

        return new DailySignalReport(
                "DSA 每日决策信号融合版",
                SOURCE_PROJECT,
                SOURCE_COMMIT,
                marketContext(techReport, mispricingReport, signals),
                actionCounts,
                strategyPlaybooks(),
                signals,
                Instant.now()
        );
    }

    private List<StrategyFeedbackSummary> feedbackSummariesSafely() {
        if (strategyFeedbackService == null) {
            return List.of();
        }
        try {
            List<StrategyFeedbackSummary> summaries = strategyFeedbackService.summaries();
            return summaries == null ? List.of() : summaries;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<DailyDecisionSignal> consolidateDuplicateSymbols(
            List<DailyDecisionSignal> rawSignals,
            List<StrategyFeedbackSummary> feedbackSummaries
    ) {
        Map<String, List<DailyDecisionSignal>> bySymbol = rawSignals.stream()
                .collect(Collectors.groupingBy(this::dedupeKey, LinkedHashMap::new, Collectors.toList()));
        List<DailyDecisionSignal> result = new ArrayList<>();
        bySymbol.forEach((ignored, group) -> {
            if (group.size() == 1 || group.get(0).symbol() == null || group.get(0).symbol().isBlank()) {
                result.addAll(group);
            } else {
                result.add(unifiedSignal(group, feedbackSummaries));
            }
        });
        return result;
    }

    private DailyDecisionSignal unifiedSignal(
            List<DailyDecisionSignal> group,
            List<StrategyFeedbackSummary> feedbackSummaries
    ) {
        DailyDecisionSignal primary = group.stream()
                .max(Comparator.comparingInt((DailyDecisionSignal signal) -> actionPriority(signal.action()))
                        .thenComparing(DailyDecisionSignal::confidence)
                        .thenComparing(DailyDecisionSignal::score, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(group.get(0));
        DailyDecisionSignal freshest = group.stream()
                .filter(signal -> signal.marketTimestamp() != null)
                .max(Comparator.comparing(DailyDecisionSignal::marketTimestamp))
                .orElse(primary);
        TradingAdvice unifiedAdvice = strategyDecisionBroker.resolve(group.stream()
                .map(this::toStrategyDecisionInput)
                .toList(), feedbackSummaries);
        List<String> sourceLabels = group.stream()
                .map(DailyDecisionSignal::sourceLabel)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        return new DailyDecisionSignal(
                0,
                primary.symbol(),
                primary.name(),
                primary.market(),
                "strategy_broker",
                "统一决策中枢",
                normalizeAction(unifiedAdvice.action()),
                unifiedAdvice.actionLabel(),
                unifiedAdvice.confidence(),
                maxScore(group),
                freshest.recommendedPrice(),
                freshest.marketTimestamp(),
                horizonFor(unifiedAdvice.action()),
                primary.marketPhase(),
                unifiedAdvice,
                mergeTags(group),
                "统一决策中枢：" + unifiedAdvice.summary() + " 来源：" + String.join(" / ", sourceLabels),
                join(group.stream().map(DailyDecisionSignal::riskSummary).toList()),
                join(group.stream().map(DailyDecisionSignal::catalystSummary).toList()),
                merge(unifiedAdvice.riskControls(), group.stream()
                        .flatMap(signal -> signal.watchConditions().stream())
                        .toList()),
                mergeEvidence(group)
        );
    }

    private DailyDecisionSignal applyStrategyFeedback(
            DailyDecisionSignal signal,
            List<StrategyFeedbackSummary> feedbackSummaries
    ) {
        if ("strategy_broker".equals(signal.sourceType())) {
            return signal;
        }
        List<StrategyFeedbackSummary> matchedFeedback = matchingFeedback(signal, feedbackSummaries);
        if (matchedFeedback.isEmpty()) {
            return signal;
        }
        TradingAdvice adjustedAdvice = strategyDecisionBroker.adjustWithFeedback(signal.todayAdvice(), matchedFeedback);
        return new DailyDecisionSignal(
                signal.rank(),
                signal.symbol(),
                signal.name(),
                signal.market(),
                signal.sourceType(),
                signal.sourceLabel(),
                normalizeAction(adjustedAdvice.action()),
                adjustedAdvice.actionLabel(),
                adjustedAdvice.confidence(),
                signal.score(),
                signal.recommendedPrice(),
                signal.marketTimestamp(),
                horizonFor(adjustedAdvice.action()),
                signal.marketPhase(),
                adjustedAdvice,
                signal.strategyTags(),
                signal.reason(),
                signal.riskSummary(),
                signal.catalystSummary(),
                merge(adjustedAdvice.riskControls(), signal.watchConditions()),
                appendFeedbackEvidence(signal.evidence(), matchedFeedback)
        );
    }

    private StrategyDecisionInput toStrategyDecisionInput(DailyDecisionSignal signal) {
        RecommendationSource source = recommendationSource(signal.sourceType());
        return new StrategyDecisionInput(
                signal.sourceType(),
                signal.sourceLabel(),
                signal.strategyTags().isEmpty() ? signal.sourceType() : signal.strategyTags().get(0),
                signal.todayAdvice().action(),
                signal.todayAdvice().actionLabel(),
                signal.todayAdvice().confidence(),
                signal.score(),
                signal.reason(),
                signal.todayAdvice().riskControls(),
                source == null ? null : source.sourceModule(),
                source == null ? null : source.ruleVersion()
        );
    }

    private RecommendationSource recommendationSource(String sourceType) {
        return switch (sourceType == null ? "" : sourceType) {
            case "tech_tracker" -> RecommendationSource.HOT_TRACKER;
            case "mispricing" -> RecommendationSource.MISPRICING;
            case "short_term" -> RecommendationSource.SHORT_TERM;
            case "cycle" -> RecommendationSource.CYCLE_TRIAL;
            case "strategy_broker" -> RecommendationSource.DAILY_SIGNAL;
            default -> null;
        };
    }

    private List<StrategyFeedbackSummary> matchingFeedback(
            DailyDecisionSignal signal,
            List<StrategyFeedbackSummary> feedbackSummaries
    ) {
        RecommendationSource source = recommendationSource(signal.sourceType());
        if (source == null || feedbackSummaries == null || feedbackSummaries.isEmpty()) {
            return List.of();
        }
        return feedbackSummaries.stream()
                .filter(summary -> summary != null
                        && summary.adjustmentEligible()
                        && summary.reliabilityAdjustment() != null)
                .filter(summary -> source.sourceModule().equals(summary.sourceModule())
                        && source.ruleVersion().equalsIgnoreCase(summary.ruleVersion()))
                .sorted(Comparator.comparingInt(StrategyFeedbackSummary::sampleCount).reversed())
                .limit(3)
                .toList();
    }

    private List<DailySignalEvidence> appendFeedbackEvidence(
            List<DailySignalEvidence> evidence,
            List<StrategyFeedbackSummary> matchedFeedback
    ) {
        List<DailySignalEvidence> result = new ArrayList<>(evidence);
        result.add(new DailySignalEvidence(
                "历史复盘反馈",
                matchedFeedback.stream()
                        .map(summary -> summary.sourceModule()
                                + "/" + summary.ruleVersion()
                                + " " + summary.horizon()
                                + " 样本 " + summary.sampleCount()
                                + "，可靠性修正 " + signed(summary.reliabilityAdjustment()))
                        .collect(Collectors.joining("；")),
                null,
                90
        ));
        return result.stream()
                .filter(item -> item != null)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                item -> item.title() + "|" + item.summary() + "|" + item.url(),
                                item -> item,
                                (left, right) -> left,
                                LinkedHashMap::new
                        ),
                        map -> map.values().stream().limit(10).toList()
                ));
    }

    private List<String> mergeTags(List<DailyDecisionSignal> group) {
        List<String> tags = group.stream()
                .flatMap(signal -> signal.strategyTags().stream())
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        tags.add("unified_decision");
        return tags.stream().distinct().toList();
    }

    private List<DailySignalEvidence> mergeEvidence(List<DailyDecisionSignal> group) {
        return group.stream()
                .flatMap(signal -> signal.evidence().stream())
                .filter(item -> item != null)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                item -> item.title() + "|" + item.summary() + "|" + item.url(),
                                item -> item,
                                (left, right) -> left,
                                LinkedHashMap::new
                        ),
                        map -> map.values().stream().limit(10).toList()
                ));
    }

    private BigDecimal maxScore(List<DailyDecisionSignal> group) {
        return group.stream()
                .map(DailyDecisionSignal::score)
                .filter(score -> score != null)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private DailyDecisionSignal fromTech(TechTrackedStock stock) {
        TradingAdvice advice = stock.todayAdvice();
        return new DailyDecisionSignal(
                0,
                stock.symbol(),
                stock.name(),
                market(stock.symbol()),
                "tech_tracker",
                "热门追踪池",
                normalizeAction(advice.action()),
                advice.actionLabel(),
                advice.confidence(),
                stock.score().finalScore(),
                stock.latestPrice(),
                stock.marketTimestamp(),
                horizonFor(advice.action()),
                "intraday",
                advice,
                techStrategies(stock),
                stock.reason(),
                join(stock.risks()),
                join(stock.strengths()),
                merge(advice.riskControls(), stock.entryRules()),
                stock.evidence().stream().map(this::fromTechEvidence).toList()
        );
    }

    private DailyDecisionSignal fromMispricing(MispricedAsset asset) {
        TradingAdvice advice = asset.todayAdvice();
        return new DailyDecisionSignal(
                0,
                asset.symbol(),
                asset.name(),
                market(asset.symbol()),
                "mispricing",
                "错杀估值池",
                normalizeAction(advice.action()),
                advice.actionLabel(),
                advice.confidence(),
                asset.score().finalScore(),
                asset.latestPrice(),
                asset.marketTimestamp(),
                horizonFor(advice.action()),
                "intraday",
                advice,
                mispricingStrategies(asset),
                asset.reason(),
                join(asset.risks()),
                join(asset.strengths()),
                merge(advice.riskControls(), asset.entryRules()),
                asset.evidence().stream().map(this::fromMispricingEvidence).toList()
        );
    }

    private DailyMarketContext marketContext(
            TechTrackingReport techReport,
            MispricingReport mispricingReport,
            List<DailyDecisionSignal> signals
    ) {
        long addCount = signals.stream().filter(signal -> "add".equals(signal.action())).count();
        long reduceCount = signals.stream().filter(signal -> "reduce".equals(signal.action()) || "sell".equals(signal.action())).count();
        String positionCap = reduceCount > addCount
                ? "新增仓位 <= 20%，先处理减仓信号"
                : "单票 <= 10%，新增仓位分批执行";
        List<String> riskTags = new ArrayList<>();
        if (mispricingReport.styleHeat().heatScore().compareTo(new BigDecimal("80")) >= 0) {
            riskTags.add("热门方向高过热");
        }
        if (techReport.candidates().stream().anyMatch(stock -> "THEME_ONLY".equals(stock.action()))) {
            riskTags.add("科技高估值拥挤");
        }
        if (reduceCount > 0) {
            riskTags.add("存在减仓/退出信号");
        }
        if (riskTags.isEmpty()) {
            riskTags.add("风险中性");
        }
        String summary = "融合 daily_stock_analysis 的每日市场上下文思想：先判断热门拥挤和组合仓位上限，再把科技追踪、错杀估值和今日建议沉淀为可复核信号。"
                + " 当前科技候选 " + techReport.candidateCount()
                + " 只，错杀候选 " + mispricingReport.candidateCount()
                + " 只，错杀热度分 " + mispricingReport.styleHeat().heatScore()
                + "，今日加仓信号 " + addCount
                + " 个，减仓/卖出信号 " + reduceCount + " 个。";
        return new DailyMarketContext(
                "cn",
                LocalDate.now(),
                summary,
                riskTags,
                positionCap,
                SOURCE_PROJECT + " daily_market_context"
        );
    }

    private List<StrategyPlaybook> strategyPlaybooks() {
        return List.of(
                new StrategyPlaybook(
                        "shrink_pullback",
                        "缩量回踩",
                        "trend",
                        "上升趋势中等待回踩 MA5/MA10，要求回调缩量并守住均线支撑。",
                        List.of(1, 2, 4),
                        List.of("get_daily_history", "analyze_trend", "get_realtime_quote"),
                        List.of("MA5 > MA10 > MA20", "价格回踩 MA5/MA10 附近", "回调缩量且没有明确利空"),
                        List.of("放量跌破 MA20", "回踩后不能收回关键均线", "出现核心利空"),
                        "缩量回踩 MA5/MA10 且守住支撑时提高加仓优先级"
                ),
                new StrategyPlaybook(
                        "expectation_repricing",
                        "预期重估",
                        "framework",
                        "识别业绩、政策、订单或估值预期变化，判断是修复、兑现还是落空。",
                        List.of(3, 5, 6),
                        List.of("search_stock_news", "get_stock_info", "get_realtime_quote", "analyze_trend"),
                        List.of("硬信息改变盈利或估值预期", "价格尚未充分反映正向预期差", "估值和盈利质量能互相验证"),
                        List.of("利好兑现后高位滞涨", "预期被财报或公告证伪", "估值提升缺少盈利支撑"),
                        "正向预期差且未充分反映时提高评分，预期兑现或落空时降级"
                ),
                new StrategyPlaybook(
                        "growth_quality",
                        "成长质量",
                        "framework",
                        "用收入、利润、ROE、经营现金流和行业空间筛掉只有概念、没有兑现的成长股。",
                        List.of(2, 3, 5),
                        List.of("get_stock_info", "get_realtime_quote", "search_stock_news", "analyze_trend"),
                        List.of("收入、利润、现金流同向改善", "ROE 稳定且高于行业常态", "行业景气和公司订单互相验证"),
                        List.of("增收不增利", "现金流明显弱于利润", "高估值但成长放缓"),
                        "四项质量指标同向改善时提高核心跟踪等级"
                ),
                new StrategyPlaybook(
                        "event_driven",
                        "事件驱动",
                        "framework",
                        "围绕业绩预告、订单、政策、产品或风险事件评估催化强度和兑现概率。",
                        List.of(3, 5),
                        List.of("search_stock_news", "get_realtime_quote", "analyze_trend"),
                        List.of("事件可信且未被价格充分反映", "事件能明确影响收入、利润率或估值", "市场反应没有过度透支"),
                        List.of("公告不及预期", "事件只影响情绪不影响基本面", "高位放量滞涨"),
                        "高可信正向事件加分，负面事件或兑现过热降级"
                )
        );
    }

    private List<String> techStrategies(TechTrackedStock stock) {
        List<String> tags = new ArrayList<>();
        tags.add("growth_quality");
        if ("WAIT_PULLBACK".equals(stock.action()) || "ADD".equals(stock.todayAdvice().action())) {
            tags.add("shrink_pullback");
        }
        if ("THEME_ONLY".equals(stock.action()) || stock.pbRatio() != null && stock.pbRatio().compareTo(new BigDecimal("20")) > 0) {
            tags.add("expectation_repricing");
        }
        if (stock.strengths().stream().anyMatch(text -> text.contains("订单") || text.contains("政策"))) {
            tags.add("event_driven");
        }
        return tags.stream().distinct().toList();
    }

    private List<String> mispricingStrategies(MispricedAsset asset) {
        List<String> tags = new ArrayList<>();
        tags.add("expectation_repricing");
        if (asset.score().qualityScore().compareTo(new BigDecimal("80")) >= 0) {
            tags.add("growth_quality");
        }
        if (asset.assetGroup().contains("周期") || "CYCLICAL_OBSERVE".equals(asset.action())) {
            tags.add("event_driven");
        }
        if ("ADD".equals(asset.todayAdvice().action())) {
            tags.add("shrink_pullback");
        }
        return tags.stream().distinct().toList();
    }

    private DailyDecisionSignal rerank(DailyDecisionSignal signal, int rank) {
        return new DailyDecisionSignal(
                rank,
                signal.symbol(),
                signal.name(),
                signal.market(),
                signal.sourceType(),
                signal.sourceLabel(),
                signal.action(),
                signal.actionLabel(),
                signal.confidence(),
                signal.score(),
                signal.recommendedPrice(),
                signal.marketTimestamp(),
                signal.horizon(),
                signal.marketPhase(),
                signal.todayAdvice(),
                signal.strategyTags(),
                signal.reason(),
                signal.riskSummary(),
                signal.catalystSummary(),
                signal.watchConditions(),
                signal.evidence()
        );
    }

    private DailySignalEvidence fromTechEvidence(TechEvidenceItem item) {
        return new DailySignalEvidence(item.title(), item.summary(), item.url(), item.weight());
    }

    private DailySignalEvidence fromMispricingEvidence(MispricingEvidenceItem item) {
        return new DailySignalEvidence(item.title(), item.summary(), item.url(), item.weight());
    }

    private String normalizeAction(String action) {
        return switch (action) {
            case "ADD" -> "add";
            case "LIGHT_TRIAL" -> "trial";
            case "HOLD" -> "hold";
            case "BATCH_SELL" -> "reduce";
            case "SELL_ALL" -> "sell";
            default -> "watch";
        };
    }

    private String horizonFor(String action) {
        return switch (action) {
            case "ADD" -> "3d";
            case "LIGHT_TRIAL" -> "2d";
            case "BATCH_SELL", "SELL_ALL" -> "1d";
            default -> "5d";
        };
    }

    private int actionPriority(String action) {
        return switch (action) {
            case "sell" -> 5;
            case "reduce" -> 4;
            case "add" -> 3;
            case "trial" -> 3;
            case "hold" -> 2;
            default -> 1;
        };
    }

    private String dedupeKey(DailyDecisionSignal signal) {
        if (signal.symbol() == null || signal.symbol().isBlank()) {
            return signal.sourceType() + "#" + signal.rank();
        }
        return signal.symbol();
    }

    private String market(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "unknown";
        }
        if (symbol.startsWith("6")) {
            return "sh";
        }
        return "sz";
    }

    private int positive(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return Math.min(value, 50);
    }

    private BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return fallback;
        }
        return value.min(new BigDecimal("100"));
    }

    private String signed(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        BigDecimal normalized = value.setScale(2, java.math.RoundingMode.HALF_UP);
        return normalized.signum() > 0 ? "+" + normalized : normalized.toString();
    }

    private List<String> merge(List<String> primary, List<String> secondary) {
        return Stream.concat(primary.stream(), secondary.stream())
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private String join(List<String> values) {
        return values.stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(4)
                .collect(Collectors.joining("；"));
    }
}

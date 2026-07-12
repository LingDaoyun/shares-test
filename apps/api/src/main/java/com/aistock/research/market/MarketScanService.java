package com.aistock.research.market;

import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.EvidenceCompletenessInput;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.quality.RecommendationEvidenceBundle;
import com.aistock.research.quality.RecommendationEvidenceEnrichmentService;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.universe.UniversalAshareScreener;
import com.aistock.research.universe.UniversalScreenCandidate;
import com.aistock.research.universe.UniversalScreenMode;
import com.aistock.research.universe.UniversalScreenReport;
import com.aistock.research.universe.UniversalScreenRequest;
import com.aistock.research.universe.UniversalScreenRuleSet;
import com.aistock.research.valuation.ValuationContextState;
import com.aistock.research.universe.UniversalScreenTraceStep;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

@Service
public class MarketScanService {

    private static final String FINANCIAL_HISTORY_GAP = "近三年点时财报尚未接入本轮全市场扫描，代理财务分不能作为买入证据。";

    private static final BigDecimal DEFAULT_MAX_RISE_FOR_ENTRY = new BigDecimal("4.00");
    private static final BigDecimal DEFAULT_MAX_SINGLE_POSITION = new BigDecimal("10.00");

    private final UniversalAshareScreener universalScreener;
    private final EvidenceCompletenessService evidenceCompletenessService;
    private final RecommendationEvidenceEnrichmentService evidenceEnrichmentService;

    public MarketScanService(UniversalAshareScreener universalScreener) {
        this(universalScreener, new EvidenceCompletenessService(), new RecommendationEvidenceEnrichmentService());
    }

    @Autowired
    public MarketScanService(
            UniversalAshareScreener universalScreener,
            EvidenceCompletenessService evidenceCompletenessService,
            RecommendationEvidenceEnrichmentService evidenceEnrichmentService
    ) {
        this.universalScreener = universalScreener;
        this.evidenceCompletenessService = evidenceCompletenessService;
        this.evidenceEnrichmentService = evidenceEnrichmentService;
    }

    public MarketScanReport report(
            Integer limit,
            Integer scanLimit,
            BigDecimal minAmount,
            BigDecimal maxPe,
            BigDecimal maxPb
    ) {
        return report(limit, scanLimit, minAmount, maxPe, maxPb, null, null, null, null);
    }

    public MarketScanReport report(
            Integer limit,
            Integer scanLimit,
            BigDecimal minAmount,
            BigDecimal maxPe,
            BigDecimal maxPb,
            BigDecimal minFinancialScore,
            Boolean excludeSideways,
            Boolean includeNorthExchange,
            String mode
    ) {
        UniversalScreenReport universal = universalScreener.screen(new UniversalScreenRequest(
                limit,
                scanLimit,
                minAmount,
                maxPe,
                maxPb,
                minFinancialScore,
                excludeSideways,
                includeNorthExchange,
                mode
        ));
        UniversalScreenRuleSet universalRuleSet = universal.ruleSet();
        UniversalScreenMode screenMode = UniversalScreenMode.fromExternal(universalRuleSet.mode());
        List<MarketScanCandidate> candidates = IntStream.range(0, universal.candidates().size())
                .mapToObj(index -> toMarketCandidate(universal.candidates().get(index), index + 1, screenMode))
                .toList();
        return new MarketScanReport(
                "沪深北 A 股全市场扫描",
                universal.universeCount(),
                universal.reviewedCount(),
                candidates.size(),
                universal.quoteNote() + " 市场扫描页不使用缓存或示例数据做交易判断。",
                universal.coverage(),
                methodology(screenMode),
                new MarketScanRuleSet(
                        universalRuleSet.scanLimit(),
                        universalRuleSet.minAmount(),
                        universalRuleSet.maxPe(),
                        universalRuleSet.maxPb(),
                        DEFAULT_MAX_RISE_FOR_ENTRY,
                        DEFAULT_MAX_SINGLE_POSITION,
                        universalRuleSet.minFinancialScore(),
                        universalRuleSet.excludeSideways(),
                        universalRuleSet.includeNorthExchange(),
                        universalRuleSet.mode()
                ),
                universal.stageStats(),
                candidates,
                universal.exclusionsSample(),
                universal.generatedAt()
        );
    }

    private MarketScanCandidate toMarketCandidate(
            UniversalScreenCandidate candidate,
            int rank,
            UniversalScreenMode mode
    ) {
        RecommendationEvidenceBundle evidenceBundle = evidenceEnrichmentService.enrichForList(candidate.symbol());
        EvidenceCompleteness completeness = marketEvidenceCompleteness(candidate, evidenceBundle);
        TradingAdvice gatedAdvice = evidenceCompletenessService.gateAdvice(todayAdvice(candidate, mode), completeness);
        return new MarketScanCandidate(
                rank,
                candidate.symbol(),
                candidate.name(),
                candidate.market(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.marketTimestamp(),
                candidate.changePercent(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.amount(),
                candidate.valuationContext(),
                new MarketScanScoreBreakdown(
                        candidate.score().valuationScore(),
                        candidate.score().liquidityScore(),
                        candidate.score().trendScore(),
                        candidate.score().financialScore(),
                        candidate.score().riskScore(),
                        candidate.score().finalScore()
                ),
                candidate.action(),
                candidate.actionLabel(),
                candidate.reason(),
                gatedAdvice,
                tags(candidate),
                candidate.strengths(),
                candidate.risks(),
                mergeGaps(mergeGaps(candidate.dataGaps(), evidenceBundle.dataGaps()), List.of(FINANCIAL_HISTORY_GAP)),
                completeness,
                evidenceBundle,
                candidate.trace().stream().map(this::toTrace).toList()
        );
    }

    private EvidenceCompleteness marketEvidenceCompleteness(UniversalScreenCandidate candidate, RecommendationEvidenceBundle evidenceBundle) {
        boolean hasValuationEvidence = candidate.valuationContext().state() != ValuationContextState.MISSING
                && candidate.valuationContext().rawPe() != null
                && candidate.valuationContext().rawPb() != null;
        return evidenceCompletenessService.evaluate(EvidenceCompletenessInput.longTerm(
                candidate.latestPrice() != null && candidate.amount() != null,
                hasValuationEvidence,
                candidate.trace().stream().anyMatch(step -> "RISK".equals(step.step())),
                false,
                evidenceBundle.hasExecutableConsensus(),
                evidenceBundle.hasIndustryComparison(),
                false,
                mergeGaps(mergeGaps(candidate.dataGaps(), evidenceBundle.dataGaps()), List.of(FINANCIAL_HISTORY_GAP))
        ));
    }

    private List<String> mergeGaps(List<String> primary, List<String> secondary) {
        java.util.ArrayList<String> merged = new java.util.ArrayList<>();
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

    private TradingAdvice todayAdvice(UniversalScreenCandidate candidate, UniversalScreenMode mode) {
        if (mode != UniversalScreenMode.VALUE) {
            return switch (mode) {
                case ALL -> new TradingAdvice(
                        "WAIT",
                        "仅作研究",
                        candidate.score().finalScore().intValue(),
                        "全市场资格通过只表示数据可进入后续策略，不构成买入建议。",
                        List.of(candidate.reason()),
                        List.of("请选择长线、周期或短线策略继续评估", "未通过独立策略前不执行买入")
                );
                case CYCLE -> new TradingAdvice(
                        "WAIT",
                        "等待周期证据",
                        candidate.score().finalScore().intValue(),
                        "已进入周期研究池，但供需、库存、价格和成本证据尚未在本页完成。",
                        List.of(candidate.reason()),
                        List.of("转入周期试仓模块复核", "负 PE 或低 PB 不能单独触发买入")
                );
                case SHORT_TERM -> new TradingAdvice(
                        "WAIT",
                        "等待右侧确认",
                        candidate.score().finalScore().intValue(),
                        "已通过短线资格初筛，最终动作由右侧结构、量能和尾盘信号决定。",
                        List.of(candidate.reason()),
                        List.of("转入短线右侧模块复核", "通用排名不替代尾盘执行信号")
                );
                case VALUE -> throw new IllegalStateException("VALUE 模式建议路由异常");
            };
        }
        if ("ACCUMULATE".equals(candidate.action())) {
            return new TradingAdvice(
                    "ADD",
                    "分批加仓",
                    candidate.score().finalScore().intValue(),
                    "统一全 A 漏斗通过，今日只允许按计划小步分批。",
                    List.of(candidate.reason(), "已通过长线价值盈利代理和流动性资格，横盘未作硬排除"),
                    List.of("单票仓位上限 10%", "若财报或公告证据恶化，暂停加仓")
            );
        }
        if ("VALUE_RESEARCH".equals(candidate.action())) {
            return new TradingAdvice(
                    "WAIT",
                    "财报待补",
                    candidate.score().finalScore().intValue(),
                    "行情和估值资格已通过，但近三年点时财报尚未进入本轮评分，当前只可研究、不可加仓。",
                    List.of(candidate.reason(), FINANCIAL_HISTORY_GAP),
                    List.of("补齐年度 ROE、毛利率和经营现金流序列", "完成公告反证后再重新计算")
            );
        }
        if ("WATCH_BUY_ZONE".equals(candidate.action())) {
            return new TradingAdvice(
                    "WAIT",
                    "等确认",
                    candidate.score().finalScore().intValue(),
                    "已进入全市场重点观察，但买点需要财报、公告或价格继续确认。",
                    List.of(candidate.reason()),
                    List.of("确认前只保留观察或试探仓", "不在单日急涨时追价")
            );
        }
        if ("WAIT_PULLBACK".equals(candidate.action())) {
            return new TradingAdvice(
                    "WAIT",
                    "等回踩",
                    Math.max(55, candidate.score().finalScore().intValue() - 10),
                    "统一漏斗通过，但今天更像追涨窗口，不适合新建重仓。",
                    List.of(candidate.reason()),
                    List.of("等待回踩不破关键均线", "不在单日急涨后扩大仓位")
            );
        }
        return new TradingAdvice(
                "WAIT",
                "观望",
                Math.max(45, candidate.score().finalScore().intValue()),
                "当前通过硬过滤但综合证据不足，不给买入动作。",
                List.of(candidate.reason()),
                List.of("补齐财报质量和公告证据", "等待更明确的估值或趋势信号")
        );
    }

    private List<String> tags(UniversalScreenCandidate candidate) {
        java.util.ArrayList<String> tags = new java.util.ArrayList<>();
        tags.add("全市场候选");
        if (candidate.peTtm() != null
                && candidate.pbRatio() != null
                && candidate.peTtm().compareTo(BigDecimal.ZERO) > 0
                && candidate.pbRatio().compareTo(BigDecimal.ZERO) > 0
                && candidate.peTtm().compareTo(new BigDecimal("15")) <= 0
                && candidate.pbRatio().compareTo(new BigDecimal("1.50")) <= 0) {
            tags.add("低估值");
        }
        if ("VALUE".equals(candidate.bucket())) {
            tags.add("价值观察");
        }
        if ("CYCLE".equals(candidate.bucket())) {
            tags.add("周期修复");
        }
        if ("GROWTH".equals(candidate.bucket())) {
            tags.add("成长观察");
        }
        if (isDefensiveIndustry(candidate.industry())) {
            tags.add("红利防守");
        }
        return tags.stream().distinct().toList();
    }

    private boolean isDefensiveIndustry(String industry) {
        if (industry == null) {
            return false;
        }
        return industry.contains("银行")
                || industry.contains("保险")
                || industry.contains("电力")
                || industry.contains("公用")
                || industry.contains("高速")
                || industry.contains("铁路")
                || industry.contains("食品")
                || industry.contains("饮料")
                || industry.contains("家电")
                || industry.contains("乳品")
                || industry.contains("医药");
    }

    private MarketScanTraceStep toTrace(UniversalScreenTraceStep step) {
        return new MarketScanTraceStep(
                step.step(),
                step.title(),
                step.summary(),
                step.findings(),
                step.sourceName(),
                step.sourceUrl()
        );
    }

    private List<String> methodology(UniversalScreenMode mode) {
        return List.of(
                "统一层覆盖沪深北证券主数据、交易资格和行情质量，缺失数量通过覆盖审计直接展示。",
                "当前模式为 " + mode.name() + "，盈利、流动性、横盘和周期行业门槛由该模式独立决定。",
                "筛选阶段动作只说明研究资格；今日建议还必须通过证据完整度和风险门禁。",
                "通用全市场模式不产生买入动作，长线、周期和短线结论不能互相替代。"
        );
    }
}

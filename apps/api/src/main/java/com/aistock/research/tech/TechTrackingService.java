package com.aistock.research.tech;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.RecommendationQuality;
import com.aistock.research.trading.TradingAdvice;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class TechTrackingService {

    private static final int DEFAULT_LIMIT = 10;
    private static final TechTrackingRuleSet DEFAULT_RULE_SET = new TechTrackingRuleSet(
            new BigDecimal("80"),
            new BigDecimal("20"),
            new BigDecimal("120"),
            new BigDecimal("40"),
            new BigDecimal("5.00"),
            new BigDecimal("8.00"),
            new BigDecimal("10.00")
    );

    private final EastMoneyClient eastMoneyClient;

    public TechTrackingService(EastMoneyClient eastMoneyClient) {
        this.eastMoneyClient = eastMoneyClient;
    }

    public TechTrackingReport report(
            Integer limit,
            BigDecimal coreMaxPe,
            BigDecimal coreMaxPb,
            BigDecimal hardMaxPe,
            BigDecimal hardMaxPb
    ) {
        TechTrackingRuleSet ruleSet = resolveRuleSet(coreMaxPe, coreMaxPb, hardMaxPe, hardMaxPb);
        List<EastMoneyQuote> marketQuotes = eastMoneyClient.fetchAshareQuotes(6000);
        List<TechStockSeed> universe = marketQuotes.stream()
                .filter(this::isTradableCommonShare)
                .filter(quote -> quote.industry() != null && !quote.industry().isBlank())
                .map(this::dynamicSeed)
                .toList();
        Map<String, EastMoneyQuote> quotes = marketQuotes.stream()
                .collect(Collectors.toMap(EastMoneyQuote::symbol, Function.identity(), (left, right) -> left));

        List<ScoredTechStock> scored = universe.stream()
                .map(seed -> score(seed, quotes.get(seed.symbol()), ruleSet))
                .filter(item -> RecommendationQuality.hasSufficientLiquidity(item.stock().amount()))
                .sorted(Comparator.comparingInt((ScoredTechStock item) -> actionPriority(item.stock().action())).reversed()
                        .thenComparing((ScoredTechStock item) -> item.stock().score().finalScore(), Comparator.reverseOrder())
                        .thenComparing(item -> item.stock().rank()))
                .toList();

        int safeLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, universe.size()));
        List<ScoredTechStock> finalists = scored.stream()
                .limit(safeLimit * 3L)
                .filter(item -> !isLongSideways(item.stock().symbol()))
                .limit(safeLimit)
                .toList();
        List<TechTrackedStock> candidates = IntStream.range(0, finalists.size())
                .mapToObj(index -> rerank(finalists.get(index).stock(), index + 1))
                .toList();

        return new TechTrackingReport(
                "A股科技追踪池",
                universe.size(),
                candidates.size(),
                "行情来自东方财富；盘前或休市时最新价会回落到上一交易日收盘价，涨跌幅可能显示为 0。",
                List.of(
                    "热门追踪覆盖全 A 股行业，按当期行业涨幅、成交额、资金活跃度和产业标签动态识别热门方向。",
                        "评分 = 政策/产业主线 25% + 业绩兑现 30% + 估值容错 25% + 交易纪律 20%。",
                        "所有科技跟踪候选必须满足 8000 万以上成交额，避免主题票流动性不足导致滑点和假突破。",
                        "PE 超过硬阈值或 PB 超过硬阈值时，不给重仓追涨结论，只允许观察或小仓趋势。",
                        "最终动作不是买入建议，而是把标的放入不同跟踪队列。"
                ),
                policySignals(),
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

    private TechStockSeed dynamicSeed(EastMoneyQuote quote) {
        String industry = quote.industry();
        String themeName = industry == null ? "热门行业" : industry;
        int policy = containsHotIndustry(industry) ? 82 : 58;
        int earnings = quote.peTtm() != null && quote.peTtm().compareTo(BigDecimal.ZERO) > 0 ? 68 : 48;
        int crowding = quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("7")) > 0 ? 72 : 35;
        return new TechStockSeed(quote.symbol(), quote.name(), "HOT_" + themeName, themeName,
                policy, earnings, crowding,
                List.of("来自全 A 股动态热门行业池", "行业热度、流动性和估值将随行情重新计算"),
                List.of("热门行业可能快速退潮", "需继续核验公告、财报和订单证据"));
    }

    private boolean containsHotIndustry(String industry) {
        if (industry == null) return false;
        return industry.contains("半导体") || industry.contains("电子") || industry.contains("通信")
                || industry.contains("机器人") || industry.contains("软件") || industry.contains("医药")
                || industry.contains("新能源") || industry.contains("有色") || industry.contains("军工");
    }

    private ScoredTechStock score(TechStockSeed seed, EastMoneyQuote quote, TechTrackingRuleSet ruleSet) {
        BigDecimal policy = BigDecimal.valueOf(seed.policyScore());
        BigDecimal earnings = BigDecimal.valueOf(seed.earningsScore());
        BigDecimal valuation = valuationScore(quote, ruleSet);
        BigDecimal trading = tradingDisciplineScore(seed, quote, ruleSet);
        BigDecimal finalScore = weighted(policy, "0.25")
                .add(weighted(earnings, "0.30"))
                .add(weighted(valuation, "0.25"))
                .add(weighted(trading, "0.20"));
        TechScoreBreakdown breakdown = new TechScoreBreakdown(
                scale(policy),
                scale(earnings),
                scale(valuation),
                scale(trading),
                scale(clamp(finalScore))
        );
        ActionDecision decision = decide(seed, quote, breakdown.finalScore(), ruleSet);
        TechTrackedStock stock = new TechTrackedStock(
                0,
                seed.symbol(),
                quote == null || quote.name() == null ? seed.name() : quote.name(),
                seed.themeCode(),
                seed.themeName(),
                quote == null ? null : quote.industry(),
                quote == null ? null : quote.latestPrice(),
                quote == null ? null : quote.changePercent(),
                quote == null ? null : firstPresent(quote.peTtm(), quote.peRatio()),
                quote == null ? null : quote.pbRatio(),
                quote == null ? null : quote.amount(),
                breakdown,
                decision.action(),
                decision.label(),
                decision.reason(),
                todayAdvice(seed, quote, breakdown, decision, ruleSet),
                seed.strengths(),
                risks(seed, quote, ruleSet),
                entryRules(decision.action(), ruleSet),
                exitRules(ruleSet),
                evidence(seed)
        );
        return new ScoredTechStock(stock);
    }

    private ActionDecision decide(TechStockSeed seed, EastMoneyQuote quote, BigDecimal finalScore, TechTrackingRuleSet ruleSet) {
        BigDecimal pe = quote == null ? null : firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote == null ? null : quote.pbRatio();
        if (pe == null || pb == null) {
            return new ActionDecision("WATCH_DATA", "先补行情/估值", "行情或估值字段缺失，先进入数据复核队列。");
        }
        if (pe.compareTo(BigDecimal.ZERO) < 0 || seed.earningsScore() < 45) {
            return new ActionDecision("AVOID_CHASE", "不追", "盈利兑现不足或估值口径失真，不适合按科技主线追涨。");
        }
        if (pe.compareTo(ruleSet.hardMaxPe()) > 0 || pb.compareTo(ruleSet.hardMaxPb()) > 0) {
            return new ActionDecision("THEME_ONLY", "主题小仓/只观察", "估值超过硬阈值，只能作为主题弹性样本，不能重仓追。");
        }
        if (finalScore.compareTo(new BigDecimal("78")) >= 0
                && seed.earningsScore() >= 75
                && pe.compareTo(ruleSet.coreMaxPe()) <= 0
                && pb.compareTo(ruleSet.coreMaxPb()) <= 0) {
            return new ActionDecision("WAIT_PULLBACK", "回踩重点跟踪", "政策、业绩和估值容错匹配度较好，等待回踩确认优先。");
        }
        if (finalScore.compareTo(new BigDecimal("70")) >= 0) {
            return new ActionDecision("SMALL_TREND", "小仓趋势跟踪", "主线和业绩较强，但估值或拥挤度限制仓位。");
        }
        if (finalScore.compareTo(new BigDecimal("62")) >= 0) {
            return new ActionDecision("WATCH_CONFIRM", "观察等确认", "方向成立，但需要等待业绩、订单或估值进一步确认。");
        }
        return new ActionDecision("AVOID_CHASE", "不追", "主线、业绩或估值至少一项不满足追踪纪律。");
    }

    private BigDecimal valuationScore(EastMoneyQuote quote, TechTrackingRuleSet ruleSet) {
        if (quote == null) {
            return new BigDecimal("35");
        }
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        BigDecimal peScore = scorePe(pe, ruleSet);
        BigDecimal pbScore = scorePb(pb, ruleSet);
        return clamp(weighted(peScore, "0.65").add(weighted(pbScore, "0.35")));
    }

    private BigDecimal scorePe(BigDecimal pe, TechTrackingRuleSet ruleSet) {
        if (pe == null) {
            return new BigDecimal("45");
        }
        if (pe.compareTo(BigDecimal.ZERO) < 0) {
            return new BigDecimal("15");
        }
        if (pe.compareTo(new BigDecimal("40")) <= 0) {
            return new BigDecimal("95");
        }
        if (pe.compareTo(new BigDecimal("60")) <= 0) {
            return new BigDecimal("82");
        }
        if (pe.compareTo(ruleSet.coreMaxPe()) <= 0) {
            return new BigDecimal("68");
        }
        if (pe.compareTo(ruleSet.hardMaxPe()) <= 0) {
            return new BigDecimal("48");
        }
        if (pe.compareTo(new BigDecimal("200")) <= 0) {
            return new BigDecimal("25");
        }
        return new BigDecimal("10");
    }

    private BigDecimal scorePb(BigDecimal pb, TechTrackingRuleSet ruleSet) {
        if (pb == null) {
            return new BigDecimal("45");
        }
        if (pb.compareTo(new BigDecimal("6")) <= 0) {
            return new BigDecimal("95");
        }
        if (pb.compareTo(new BigDecimal("10")) <= 0) {
            return new BigDecimal("82");
        }
        if (pb.compareTo(ruleSet.coreMaxPb()) <= 0) {
            return new BigDecimal("62");
        }
        if (pb.compareTo(ruleSet.hardMaxPb()) <= 0) {
            return new BigDecimal("35");
        }
        return new BigDecimal("12");
    }

    private BigDecimal tradingDisciplineScore(TechStockSeed seed, EastMoneyQuote quote, TechTrackingRuleSet ruleSet) {
        BigDecimal score = BigDecimal.valueOf(100L - seed.crowdingRisk());
        if (quote == null) {
            return score.subtract(new BigDecimal("20"));
        }
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        if (pe != null && pe.compareTo(ruleSet.coreMaxPe()) > 0) {
            score = score.subtract(new BigDecimal("12"));
        }
        if (pb != null && pb.compareTo(ruleSet.coreMaxPb()) > 0) {
            score = score.subtract(new BigDecimal("18"));
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("7")) > 0) {
            score = score.subtract(new BigDecimal("16"));
        }
        return clamp(score);
    }

    private List<String> risks(TechStockSeed seed, EastMoneyQuote quote, TechTrackingRuleSet ruleSet) {
        List<String> dynamic = new java.util.ArrayList<>(seed.risks());
        BigDecimal pe = quote == null ? null : firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote == null ? null : quote.pbRatio();
        if (pe != null && pe.compareTo(ruleSet.coreMaxPe()) > 0) {
            dynamic.add("PE 超过核心跟踪阈值 " + ruleSet.coreMaxPe() + "，只能降低仓位或等待业绩消化估值");
        }
        if (pb != null && pb.compareTo(ruleSet.coreMaxPb()) > 0) {
            dynamic.add("PB 超过核心跟踪阈值 " + ruleSet.coreMaxPb() + "，高预期回撤风险更高");
        }
        if (!RecommendationQuality.hasSufficientLiquidity(quote)) {
            dynamic.add(RecommendationQuality.liquidityRiskText());
        }
        return dynamic.stream().distinct().toList();
    }

    private List<String> entryRules(String action, TechTrackingRuleSet ruleSet) {
        if ("WAIT_PULLBACK".equals(action)) {
            return List.of(
                    "不追连续大阳线，优先等待回踩 5 日/10 日均线企稳",
                    "从短期高点回落 " + ruleSet.pullbackWatchPercent() + "% 左右且成交缩量后再评估",
                    "再次放量上攻并且 PE/PB 未突破阈值时升级为行动候选"
            );
        }
        if ("SMALL_TREND".equals(action) || "THEME_ONLY".equals(action)) {
            return List.of(
                    "只允许小仓位趋势跟踪，单票仓位不超过 " + ruleSet.maxSinglePositionPercent() + "%",
                    "必须等回踩或分歧日，不在涨停或连续拉升后加仓",
                    "后续财报无法继续兑现时立即降级"
            );
        }
        return List.of(
                "先补充订单、业绩或价格证据",
                "等估值回落到规则阈值以内再进入交易候选",
                "未确认前只保留观察，不做追涨动作"
        );
    }

    private List<String> exitRules(TechTrackingRuleSet ruleSet) {
        return List.of(
                "跌破放量启动位或有效跌破 20 日线，先减仓",
                "股价继续上涨但订单/利润/行业价格没有同步验证，分批锁利润",
                "PE 或 PB 突破硬阈值后，不再追加仓位",
                "从阶段高点回撤超过 " + ruleSet.stopLossPercent() + "% 且没有基本面新证据，退出交易队列"
        );
    }

    private List<TechEvidenceItem> evidence(TechStockSeed seed) {
        return List.of(
                new TechEvidenceItem(
                        seed.themeName() + "主线",
                        String.join("；", seed.strengths()),
                        null,
                        80
                ),
                new TechEvidenceItem(
                        "追涨纪律",
                        "估值阈值、回踩确认和仓位上限由页面参数控制，避免把主题热度误当成无条件买入。",
                        null,
                        90
                )
        );
    }

    private TradingAdvice todayAdvice(
            TechStockSeed seed,
            EastMoneyQuote quote,
            TechScoreBreakdown score,
            ActionDecision decision,
            TechTrackingRuleSet ruleSet
    ) {
        if (quote == null) {
            return new TradingAdvice(
                    "WAIT",
                    "观察",
                    30,
                    "今日行情或估值字段缺失，先不输出买卖动作。",
                    List.of("缺少最新价、涨跌幅或估值数据"),
                    List.of("补齐行情源后重新计算", "数据缺失时不加仓")
            );
        }
        BigDecimal pe = firstPresent(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        BigDecimal change = quote.changePercent() == null ? BigDecimal.ZERO : quote.changePercent();
        boolean hardValuationRisk = pe != null && pe.compareTo(ruleSet.hardMaxPe()) > 0
                || pb != null && pb.compareTo(ruleSet.hardMaxPb()) > 0;

        if ("AVOID_CHASE".equals(decision.action())) {
            return new TradingAdvice(
                    "SELL_ALL",
                    "全仓卖出",
                    72,
                    "盈利或估值不支持继续按科技主线持有，若已有仓位优先退出。",
                    List.of(decision.reason(), valuationFinding(pe, pb, ruleSet)),
                    List.of("不要用主题热度替代业绩验证", "重新进入前需要财报或订单证据改善")
            );
        }
        if (hardValuationRisk) {
            return new TradingAdvice(
                    "BATCH_SELL",
                    "分批卖出",
                    74,
                    "估值超过硬阈值，若已有仓位应先锁定利润并降低暴露。",
                    List.of(valuationFinding(pe, pb, ruleSet), "硬阈值之外不再追加仓位"),
                    List.of("保留观察仓即可", "等业绩消化估值或估值回落后再评估")
            );
        }
        if ("WAIT_PULLBACK".equals(decision.action())) {
            if (change.compareTo(ruleSet.pullbackWatchPercent().negate()) <= 0) {
                return new TradingAdvice(
                        "ADD",
                        "加仓",
                        82,
                        "今日回撤达到规则阈值，且主线、业绩和估值容错仍匹配，可分批加仓。",
                        List.of(
                                seed.themeName() + "主线仍在跟踪池前列",
                                "最终分 " + score.finalScore() + " 达到核心跟踪区间",
                                priceActionFinding(change, ruleSet),
                                valuationFinding(pe, pb, ruleSet)
                        ),
                        List.of("只能分批，不一次打满", "若收盘未企稳或放量破位，次日暂停加仓", "跌破 20 日线或回撤超过 " + ruleSet.stopLossPercent() + "% 先减仓")
                );
            }
            if (change.compareTo(new BigDecimal("-3.00")) <= 0) {
                return new TradingAdvice(
                        "ADD",
                        "加仓",
                        70,
                        "今日回撤已接近规则阈值，可小仓试探，但还不到重仓加仓点。",
                        List.of(
                                "回撤接近 " + ruleSet.pullbackWatchPercent() + "% 观察阈值",
                                "最终分 " + score.finalScore() + " 仍在核心跟踪区间",
                                valuationFinding(pe, pb, ruleSet)
                        ),
                        List.of("先用计划仓位的一小部分试探", "必须观察收盘是否收回日内低位", "若明日继续放量下杀，不追加")
                );
            }
            return new TradingAdvice(
                    "HOLD",
                    "持有",
                    66,
                    "标的质量仍可跟踪，但今日回踩深度不够，不适合主动加仓。",
                    List.of(decision.reason(), priceActionFinding(change, ruleSet)),
                    List.of("等回撤接近 " + ruleSet.pullbackWatchPercent() + "% 或缩量企稳", "不追单日反弹")
            );
        }
        if ("SMALL_TREND".equals(decision.action())) {
            if (change.compareTo(new BigDecimal("7.00")) >= 0) {
                return new TradingAdvice(
                        "BATCH_SELL",
                        "分批卖出",
                        68,
                        "小仓趋势标的今日涨幅过大，优先分批锁定利润。",
                        List.of(priceActionFinding(change, ruleSet), "该标的不是核心重仓结论"),
                        List.of("保留观察仓等待趋势延续", "回撤企稳后再重新计算")
                );
            }
            return new TradingAdvice(
                    "HOLD",
                    "持有",
                    62,
                    "主线仍可跟踪，但估值或拥挤度限制仓位，今日以持有为主。",
                    List.of(decision.reason(), valuationFinding(pe, pb, ruleSet)),
                    List.of("不超过单票仓位上限 " + ruleSet.maxSinglePositionPercent() + "%", "只有回踩确认后才考虑小幅加仓")
            );
        }
        if ("THEME_ONLY".equals(decision.action())) {
            return new TradingAdvice(
                    "BATCH_SELL",
                    "分批卖出",
                    64,
                    "主题弹性可以观察，但估值或拥挤度过高，已有仓位应降低暴露。",
                    List.of(decision.reason(), valuationFinding(pe, pb, ruleSet)),
                    List.of("不要把主题观察当成重仓理由", "等财报兑现后再升级")
            );
        }
        return new TradingAdvice(
                "HOLD",
                "持有",
                55,
                "今日证据不足以加仓或卖出，保持观察。",
                List.of(decision.reason(), valuationFinding(pe, pb, ruleSet)),
                List.of("等订单、财报或估值回落信号", "若跌破趋势支撑再降级")
        );
    }

    private List<TechEvidenceItem> policySignals() {
        return List.of(
                new TechEvidenceItem(
                        "人工智能+与智能经济",
                        "政策端持续强调人工智能、数据要素、算力基础设施和产业应用，AI 算力硬件是当前业绩兑现最直接的链条。",
                        "https://www.ndrc.gov.cn/",
                        95
                ),
                new TechEvidenceItem(
                        "全国一体化算力网与数字经济",
                        "算力网络、数据基础设施和数字经济建设支撑服务器、光模块、PCB、国产算力等方向。",
                        "https://www.nda.gov.cn/",
                        92
                ),
                new TechEvidenceItem(
                        "集成电路国产替代",
                        "半导体设备、材料、制造具备长期政策确定性，但高估值标的需要严格控制追涨风险。",
                        "https://www.miit.gov.cn/",
                        90
                ),
                new TechEvidenceItem(
                        "机器人与具身智能",
                        "机器人政策和产业趋势明确，但多数标的业绩兑现慢于估值，优先观察盈利质量。",
                        "https://www.miit.gov.cn/",
                        84
                )
        );
    }

    private TechTrackingRuleSet resolveRuleSet(BigDecimal coreMaxPe, BigDecimal coreMaxPb, BigDecimal hardMaxPe, BigDecimal hardMaxPb) {
        return new TechTrackingRuleSet(
                positive(coreMaxPe, DEFAULT_RULE_SET.coreMaxPe()),
                positive(coreMaxPb, DEFAULT_RULE_SET.coreMaxPb()),
                positive(hardMaxPe, DEFAULT_RULE_SET.hardMaxPe()),
                positive(hardMaxPb, DEFAULT_RULE_SET.hardMaxPb()),
                DEFAULT_RULE_SET.pullbackWatchPercent(),
                DEFAULT_RULE_SET.stopLossPercent(),
                DEFAULT_RULE_SET.maxSinglePositionPercent()
        );
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

    private TechTrackedStock rerank(TechTrackedStock stock, int rank) {
        return new TechTrackedStock(
                rank,
                stock.symbol(),
                stock.name(),
                stock.themeCode(),
                stock.themeName(),
                stock.industry(),
                stock.latestPrice(),
                stock.changePercent(),
                stock.peTtm(),
                stock.pbRatio(),
                stock.amount(),
                stock.score(),
                stock.action(),
                stock.actionLabel(),
                stock.reason(),
                stock.todayAdvice(),
                stock.strengths(),
                stock.risks(),
                stock.entryRules(),
                stock.exitRules(),
                stock.evidence()
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
            case "WAIT_PULLBACK" -> 5;
            case "SMALL_TREND" -> 4;
            case "WATCH_CONFIRM" -> 3;
            case "THEME_ONLY" -> 2;
            case "WATCH_DATA" -> 1;
            default -> 0;
        };
    }

    private String valuationFinding(BigDecimal pe, BigDecimal pb, TechTrackingRuleSet ruleSet) {
        return "PE " + display(pe) + " / PB " + display(pb)
                + "，核心阈值 PE<=" + ruleSet.coreMaxPe() + "、PB<=" + ruleSet.coreMaxPb()
                + "，硬阈值 PE<=" + ruleSet.hardMaxPe() + "、PB<=" + ruleSet.hardMaxPb() + "。";
    }

    private String priceActionFinding(BigDecimal change, TechTrackingRuleSet ruleSet) {
        return "当日涨跌幅 " + display(change) + "%，回踩观察阈值约 " + ruleSet.pullbackWatchPercent() + "%。";
    }

    private String display(BigDecimal value) {
        return value == null ? "缺失" : scale(value).toPlainString();
    }

    private record TechStockSeed(
            String symbol,
            String name,
            String themeCode,
            String themeName,
            int policyScore,
            int earningsScore,
            int crowdingRisk,
            List<String> strengths,
            List<String> risks
    ) {
    }

    private record ScoredTechStock(TechTrackedStock stock) {
    }

    private record ActionDecision(String action, String label, String reason) {
    }
}

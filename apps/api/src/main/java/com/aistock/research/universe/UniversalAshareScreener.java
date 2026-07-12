package com.aistock.research.universe;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.quality.RecommendationQuality;
import com.aistock.research.valuation.ValuationContext;
import com.aistock.research.valuation.ValuationContextCalculator;
import com.aistock.research.valuation.ValuationContextState;
import com.aistock.research.valuation.ValuationModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
public class UniversalAshareScreener {

    private static final int DEFAULT_LIMIT = 20;
    private static final int DEFAULT_SCAN_LIMIT = 6000;
    private static final int MAX_LIMIT = 80;
    private static final int MAX_SCAN_LIMIT = 6000;
    private static final BigDecimal DEFAULT_MIN_AMOUNT = RecommendationQuality.MIN_RECOMMENDED_AMOUNT;
    private static final BigDecimal DEFAULT_MAX_PE = new BigDecimal("45");
    private static final BigDecimal DEFAULT_MAX_PB = new BigDecimal("6.00");
    private static final BigDecimal DEFAULT_MIN_FINANCIAL_SCORE = new BigDecimal("45");
    private static final Duration DEFAULT_QUOTE_TIMEOUT = Duration.ofSeconds(105);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final EastMoneyClient eastMoneyClient;
    private final Duration quoteTimeout;
    private final ValuationContextCalculator valuationContextCalculator = new ValuationContextCalculator();

    @Autowired
    public UniversalAshareScreener(EastMoneyClient eastMoneyClient) {
        this(eastMoneyClient, DEFAULT_QUOTE_TIMEOUT);
    }

    UniversalAshareScreener(EastMoneyClient eastMoneyClient, Duration quoteTimeout) {
        this.eastMoneyClient = eastMoneyClient;
        this.quoteTimeout = validTimeout(quoteTimeout);
    }

    public UniversalScreenReport screen(UniversalScreenRequest request) {
        UniversalScreenRuleSet ruleSet = resolveRuleSet(request);
        UniversalScreenMode mode = UniversalScreenMode.fromExternal(ruleSet.mode());
        List<UniversalScreenStageStats> stageStats = new ArrayList<>();
        List<UniversalScreenExclusion> exclusions = new ArrayList<>();

        AshareQuoteSnapshot quoteSnapshot = fetchAshareQuotesWithinBudget(ruleSet);
        List<EastMoneyQuote> baseQuotes = quoteSnapshot.quotes();
        if (baseQuotes.isEmpty()) {
            throw new IllegalStateException("全 A 股票池为空，无法生成候选。");
        }
        List<EastMoneyQuote> quotes = mergeRealtimeQuotes(baseQuotes, ruleSet);
        UniversalScreenCoverage coverage = new UniversalScreenCoverage(
                quoteSnapshot.requestedCount(),
                quoteSnapshot.expectedCount(),
                quotes.size(),
                Math.max(0, quoteSnapshot.expectedCount() - quotes.size()),
                quotes.size() >= quoteSnapshot.expectedCount(),
                quoteSnapshot.source(),
                quoteSnapshot.fetchedAt()
        );
        stageStats.add(stats("UNIVERSE", "全 A 股票池", quotes.size(), quotes.size()));

        List<EastMoneyQuote> tradable = passStage(
                quotes,
                quote -> tradableProblem(quote, ruleSet),
                "TRADABLE",
                "可交易普通股",
                exclusions
        );
        stageStats.add(stats("TRADABLE", "可交易普通股", quotes.size(), tradable.size()));

        List<EastMoneyQuote> modeEligible = passStage(
                tradable,
                quote -> modeEligibilityProblem(quote, mode),
                "MODE_ELIGIBILITY",
                "策略资格",
                exclusions
        );
        stageStats.add(stats("MODE_ELIGIBILITY", "策略资格", tradable.size(), modeEligible.size()));

        List<EastMoneyQuote> liquid = mode.liquidityRequired()
                ? passStage(
                        modeEligible,
                        quote -> liquidityProblem(quote, ruleSet),
                        "LIQUIDITY",
                        "流动性过滤",
                        exclusions
                )
                : modeEligible;
        stageStats.add(stats("LIQUIDITY", "流动性资格", modeEligible.size(), liquid.size()));

        List<UniversalScreenCandidate> scoredCandidates = liquid.stream()
                .map(quote -> candidate(quote, ruleSet))
                .toList();
        List<UniversalScreenCandidate> scoredPool = scoredCandidates.stream()
                .sorted(Comparator.comparing((UniversalScreenCandidate item) -> item.score().finalScore()).reversed()
                        .thenComparing(UniversalScreenCandidate::amount, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UniversalScreenCandidate::symbol))
                .toList();
        stageStats.add(stats("SCORE", "中性财务排序", liquid.size(), scoredPool.size()));

        List<UniversalScreenCandidate> reviewPool = ruleSet.excludeSideways()
                ? scoredPool.stream().limit(sidewaysReviewLimit(ruleSet)).toList()
                : scoredPool;
        stageStats.add(deferredStats("DEEP_REVIEW", "深度复核预算", scoredPool.size(), reviewPool.size()));
        List<UniversalScreenCandidate> nonSideways = ruleSet.excludeSideways()
                ? passCandidateStage(
                        reviewPool,
                        candidate -> sidewaysProblem(quoteFromCandidate(candidate)),
                        "SIDEWAYS",
                        "长期横盘过滤",
                        exclusions
                )
                : reviewPool;
        stageStats.add(stats("SIDEWAYS", "横盘资格", reviewPool.size(), nonSideways.size()));

        List<UniversalScreenCandidate> scored = nonSideways.stream()
                .limit(ruleSet.limit())
                .toList();
        List<UniversalScreenCandidate> ranked = IntStream.range(0, scored.size())
                .mapToObj(index -> rerank(scored.get(index), index + 1))
                .toList();
        stageStats.add(deferredStats("FINAL", "最终候选", nonSideways.size(), ranked.size()));

        return new UniversalScreenReport(
                "沪深北全 A 统一候选漏斗",
                quotes.size(),
                reviewPool.size(),
                ranked.size(),
                quoteNote(modeEligible, liquid, coverage, mode),
                coverage,
                stageStats,
                ruleSet,
                ranked,
                exclusions.stream().limit(80).toList(),
                Instant.now()
        );
    }

    private AshareQuoteSnapshot fetchAshareQuotesWithinBudget(UniversalScreenRuleSet ruleSet) {
        CompletableFuture<AshareQuoteSnapshot> future = CompletableFuture.supplyAsync(
                () -> eastMoneyClient.fetchAshareQuoteSnapshot(ruleSet.scanLimit())
        );
        try {
            return future.get(quoteTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException(
                    "全市场实时行情加载超过 " + quoteTimeout.toMillis() + "ms 预算，本轮停止；请稍后重试或调低扫描数量。",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("全市场实时行情加载被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("全市场实时行情加载失败", cause);
        }
    }

    private List<EastMoneyQuote> mergeRealtimeQuotes(List<EastMoneyQuote> baseQuotes, UniversalScreenRuleSet ruleSet) {
        List<String> symbols = baseQuotes.stream()
                .map(EastMoneyQuote::symbol)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .toList();
        int reviewLimit = realtimeReviewLimit(ruleSet);
        List<String> reviewSymbols = symbols.stream().limit(reviewLimit).toList();
        List<EastMoneyQuote> realtimeQuotes;
        try {
            realtimeQuotes = reviewSymbols.isEmpty()
                    ? List.of()
                    : eastMoneyClient.fetchTencentQuotes(reviewSymbols, reviewSymbols.size());
        } catch (RuntimeException exception) {
            realtimeQuotes = List.of();
        }
        Map<String, EastMoneyQuote> realtimeBySymbol = realtimeQuotes.stream()
                .collect(Collectors.toMap(EastMoneyQuote::symbol, Function.identity(), (left, right) -> left));
        Map<String, EastMoneyQuote> merged = new LinkedHashMap<>();
        for (EastMoneyQuote quote : baseQuotes) {
            if (quote.symbol() == null) {
                continue;
            }
            merged.putIfAbsent(quote.symbol(), mergeQuote(quote, realtimeBySymbol.get(quote.symbol())));
        }
        return new ArrayList<>(merged.values());
    }

    private EastMoneyQuote mergeQuote(EastMoneyQuote base, EastMoneyQuote realtime) {
        if (realtime == null) {
            return base;
        }
        EastMoneyQuote priceSource = firstPositive(realtime.latestPrice()) != null
                ? realtime
                : firstPositive(base.latestPrice()) != null ? base : null;
        return new EastMoneyQuote(
                firstText(base.symbol(), realtime.symbol()),
                firstText(realtime.name(), base.name()),
                firstText(base.market(), realtime.market()),
                firstText(base.industry(), realtime.industry()),
                priceSource == null ? null : priceSource.latestPrice(),
                firstPresent(realtime.changePercent(), base.changePercent()),
                firstPresent(realtime.turnoverRate(), base.turnoverRate()),
                firstPresent(realtime.volume(), base.volume()),
                firstPositive(realtime.amount(), base.amount()),
                firstNonNull(base.peRatio(), realtime.peRatio()),
                firstNonNull(base.pbRatio(), realtime.pbRatio()),
                firstNonNull(base.peTtm(), base.peRatio(), realtime.peTtm(), realtime.peRatio()),
                priceSource == null ? firstText(base.sourceName(), realtime.sourceName()) : priceSource.sourceName(),
                priceSource == null ? firstText(base.quoteUrl(), realtime.quoteUrl()) : priceSource.quoteUrl(),
                priceSource == null ? null : priceSource.fetchedAt(),
                priceSource == null ? null : priceSource.tradeDate(),
                priceSource == null ? null : priceSource.marketTimestamp()
        );
    }

    private List<EastMoneyQuote> passStage(
            List<EastMoneyQuote> input,
            Function<EastMoneyQuote, StageProblem> problemResolver,
            String stage,
            String label,
            List<UniversalScreenExclusion> exclusions
    ) {
        List<EastMoneyQuote> passed = new ArrayList<>();
        for (EastMoneyQuote quote : input) {
            StageProblem problem = problemResolver.apply(quote);
            if (problem == null) {
                passed.add(quote);
            } else {
                exclusions.add(new UniversalScreenExclusion(
                        quote.symbol(),
                        quote.name(),
                        stage,
                        problem.reason(),
                        problem.evidence()
                ));
            }
        }
        return passed;
    }

    private List<UniversalScreenCandidate> passCandidateStage(
            List<UniversalScreenCandidate> input,
            Function<UniversalScreenCandidate, StageProblem> problemResolver,
            String stage,
            String label,
            List<UniversalScreenExclusion> exclusions
    ) {
        List<UniversalScreenCandidate> passed = new ArrayList<>();
        for (UniversalScreenCandidate candidate : input) {
            StageProblem problem = problemResolver.apply(candidate);
            if (problem == null) {
                passed.add(candidate);
            } else {
                exclusions.add(new UniversalScreenExclusion(
                        candidate.symbol(),
                        candidate.name(),
                        stage,
                        problem.reason(),
                        problem.evidence()
                ));
            }
        }
        return passed;
    }

    private int sidewaysReviewLimit(UniversalScreenRuleSet ruleSet) {
        return Math.min(ruleSet.scanLimit(), Math.max(Math.min(ruleSet.limit(), 12), 6));
    }

    private int realtimeReviewLimit(UniversalScreenRuleSet ruleSet) {
        return Math.min(ruleSet.scanLimit(), Math.max(ruleSet.limit() * 6, 80));
    }

    private EastMoneyQuote quoteFromCandidate(UniversalScreenCandidate candidate) {
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

    private StageProblem tradableProblem(EastMoneyQuote quote, UniversalScreenRuleSet ruleSet) {
        String symbol = quote.symbol();
        if (symbol == null || !symbol.matches("\\d{6}")) {
            return new StageProblem("代码异常，非标准 A 股代码", List.of("symbol=" + symbol));
        }
        if (!isSupportedAshare(symbol, ruleSet.includeNorthExchange())) {
            return new StageProblem("暂不纳入本轮市场范围", List.of("symbol=" + symbol));
        }
        String name = quote.name() == null ? "" : quote.name().toUpperCase();
        if (name.contains("ST") || name.contains("退")) {
            return new StageProblem("名称触发 ST/退市风险硬排除", List.of("name=" + quote.name()));
        }
        if (quote.latestPrice() == null || quote.latestPrice().compareTo(ZERO) <= 0) {
            return new StageProblem("实时每股价格缺失或无效", List.of("price=" + quote.latestPrice()));
        }
        return null;
    }

    private StageProblem modeEligibilityProblem(EastMoneyQuote quote, UniversalScreenMode mode) {
        if (mode.cycleIndustryRequired() && !isCycleIndustry(quote.industry())) {
            return new StageProblem(
                    "行业未进入周期研究映射",
                    List.of("industry=" + (quote.industry() == null ? "缺失" : quote.industry()))
            );
        }
        return null;
    }

    private StageProblem liquidityProblem(EastMoneyQuote quote, UniversalScreenRuleSet ruleSet) {
        if (quote.amount() == null || quote.amount().compareTo(ruleSet.minAmount()) < 0) {
            return new StageProblem(
                    "成交额低于统一流动性阈值",
                    List.of("成交额=" + plainOrMissing(quote.amount()), "阈值=" + plain(ruleSet.minAmount()))
            );
        }
        return null;
    }

    private StageProblem sidewaysProblem(EastMoneyQuote quote) {
        if (quote.symbol() != null && (quote.symbol().startsWith("4") || quote.symbol().startsWith("8") || quote.symbol().startsWith("92"))) {
            return null;
        }
        try {
            LocalDate end = LocalDate.now();
            List<EastMoneyKLine> rows = eastMoneyClient.fetchDailyKLines(quote.symbol(), end.minusDays(260), end);
            if (RecommendationQuality.isLongSideways(rows)) {
                return new StageProblem(RecommendationQuality.sidewaysRiskText(), List.of("近 120 日 K 线触发长期横盘模型"));
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }

    private UniversalScreenCandidate candidate(EastMoneyQuote quote, UniversalScreenRuleSet ruleSet) {
        BigDecimal pe = firstNonNull(quote.peTtm(), quote.peRatio());
        BigDecimal pb = quote.pbRatio();
        ValuationContext valuationContext = valuationContextCalculator.evaluate(
                pe,
                pb,
                ruleSet.maxPe(),
                ruleSet.maxPb(),
                quote.industry()
        );
        BigDecimal valuation = valuationContext.score();
        BigDecimal liquidity = liquidityScore(quote.amount(), ruleSet.minAmount());
        BigDecimal financial = financialScore(quote);
        BigDecimal trend = trendScore(quote.changePercent());
        BigDecimal risk = riskScore(quote);
        BigDecimal finalScore = weighted(financial, "0.30")
                .add(weighted(valuation, "0.10"))
                .add(weighted(liquidity, "0.20"))
                .add(weighted(trend, "0.10"))
                .add(weighted(risk, "0.30"));
        UniversalScreenScore score = new UniversalScreenScore(
                scale(financial),
                scale(valuation),
                scale(liquidity),
                scale(trend),
                scale(risk),
                scale(clamp(finalScore))
        );
        ActionDecision decision = decision(score, valuationContext, quote, ruleSet);
        return new UniversalScreenCandidate(
                0,
                quote.symbol(),
                quote.name(),
                quote.market(),
                quote.industry(),
                quote.latestPrice(),
                quote.sourceName(),
                quote.quoteUrl(),
                quote.fetchedAt(),
                quote.tradeDate(),
                quote.marketTimestamp(),
                quote.changePercent(),
                pe,
                pb,
                quote.amount(),
                valuationContext,
                score,
                bucket(quote, ruleSet),
                decision.action(),
                decision.label(),
                decision.reason(),
                strengths(quote, valuationContext, ruleSet),
                risks(quote, valuationContext),
                dataGaps(quote),
                trace(quote, valuationContext, score, ruleSet)
        );
    }

    private BigDecimal liquidityScore(BigDecimal amount, BigDecimal minAmount) {
        if (amount == null || amount.compareTo(ZERO) <= 0) {
            return new BigDecimal("35");
        }
        if (amount.compareTo(minAmount.multiply(new BigDecimal("8"))) >= 0) {
            return new BigDecimal("95");
        }
        if (amount.compareTo(minAmount.multiply(new BigDecimal("3"))) >= 0) {
            return new BigDecimal("86");
        }
        if (amount.compareTo(minAmount) >= 0) {
            return new BigDecimal("74");
        }
        return new BigDecimal("42");
    }

    private BigDecimal financialScore(EastMoneyQuote quote) {
        return new BigDecimal("50");
    }

    private BigDecimal trendScore(BigDecimal changePercent) {
        if (changePercent == null) {
            return new BigDecimal("60");
        }
        if (changePercent.compareTo(new BigDecimal("-4")) <= 0) {
            return new BigDecimal("68");
        }
        if (changePercent.compareTo(new BigDecimal("1.50")) <= 0) {
            return new BigDecimal("82");
        }
        if (changePercent.compareTo(new BigDecimal("4.00")) <= 0) {
            return new BigDecimal("64");
        }
        return new BigDecimal("38");
    }

    private BigDecimal riskScore(EastMoneyQuote quote) {
        BigDecimal score = new BigDecimal("88");
        if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("5")) > 0) {
            score = score.subtract(new BigDecimal("18"));
        }
        return clamp(score);
    }

    private ActionDecision decision(
            UniversalScreenScore score,
            ValuationContext valuationContext,
            EastMoneyQuote quote,
            UniversalScreenRuleSet ruleSet
    ) {
        UniversalScreenMode mode = UniversalScreenMode.fromExternal(ruleSet.mode());
        if (!mode.allowsBuyLikeScreeningAction()) {
            return switch (mode) {
                case ALL -> new ActionDecision("ELIGIBLE", "资格通过", "已通过共同数据质量检查，仅表示可进入后续策略研究。");
                case CYCLE -> new ActionDecision("CYCLE_RESEARCH", "周期研究", "已进入周期行业研究池，仍需供需和价格周期证据。");
                case SHORT_TERM -> new ActionDecision("SHORT_RESEARCH", "短线研究", "已通过短线初筛，仍需独立右侧结构与尾盘信号确认。");
                case VALUE -> throw new IllegalStateException("VALUE 模式动作路由异常");
            };
        }
        if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("4.00")) > 0) {
            return new ActionDecision("WAIT_PULLBACK", "冲高等回踩", "单日涨幅已经偏大，统一漏斗通过也不适合追价。");
        }
        if (valuationContext.state() == ValuationContextState.DISTORTED) {
            if (valuationContext.applicableModel() == ValuationModel.CYCLICAL) {
                return new ActionDecision(
                        "NORMALIZED_CYCLE_RESEARCH",
                        "周期正常化研究",
                        "当前盈利倍数失真，进入完整周期盈利与成本曲线复核。"
                );
            }
            return new ActionDecision(
                    "TURNAROUND_RESEARCH",
                    "困境反转研究",
                    "当前盈利倍数失真，需先证明盈利修复和现金流改善。"
            );
        }
        if (valuationContext.state() == ValuationContextState.MISSING) {
            return new ActionDecision(
                    "VALUE_RESEARCH",
                    "价值证据待补",
                    "估值数据缺失不作淘汰，但买入闸门保持关闭。"
            );
        }
        if (score.finalScore().compareTo(new BigDecimal("72")) >= 0) {
            return new ActionDecision("VALUE_RESEARCH", "财报复核", "已通过全 A 资格过滤，但财务维度仍为中性占位，需补齐点时财报后再形成买入动作。");
        }
        return new ActionDecision("WAIT_CONFIRM", "等待确认", "通过资格过滤但综合分仍需财报、公告或价格信号继续确认。");
    }

    private List<String> strengths(
            EastMoneyQuote quote,
            ValuationContext valuationContext,
            UniversalScreenRuleSet ruleSet
    ) {
        List<String> strengths = new ArrayList<>();
        UniversalScreenMode mode = UniversalScreenMode.fromExternal(ruleSet.mode());
        strengths.add(switch (mode) {
            case ALL -> "已通过代码、ST/退市名称风险和实时价格有效性检查";
            case VALUE -> "已通过长线价值研究与流动性资格，横盘不作硬排除";
            case CYCLE -> "已通过周期行业与流动性资格，负 PE 不作硬排除";
            case SHORT_TERM -> "已通过短线流动性资格和无突破横盘检查";
        });
        strengths.add("PE/PB 仅按参考带形成估值语境，不参与资格淘汰");
        strengths.add("估值语境=" + valuationContext.state().name() + "，模型=" + valuationContext.applicableModel().name());
        if (quote.amount() != null) {
            strengths.add("成交额 " + plain(quote.amount()) + "，满足流动性要求");
        }
        return strengths;
    }

    private List<String> risks(EastMoneyQuote quote, ValuationContext valuationContext) {
        List<String> risks = new ArrayList<>();
        risks.add("本层未读取真实财务历史，财务分固定为中性 50，不能据此形成买入动作");
        if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("4")) > 0) {
            risks.add("单日涨幅偏大，不适合追价");
        }
        risks.addAll(valuationContext.warnings());
        return risks;
    }

    private List<String> dataGaps(EastMoneyQuote quote) {
        List<String> gaps = new ArrayList<>();
        gaps.add("近三年点时财报尚未接入本轮排序");
        gaps.add("尚未逐只解析十年年报 PDF");
        gaps.add("尚未接入严格自由现金流和分红连续性");
        if (quote.industry() == null || quote.industry().isBlank()) {
            gaps.add("行业字段缺失，同行估值分位待补");
        }
        return gaps;
    }

    private List<UniversalScreenTraceStep> trace(
            EastMoneyQuote quote,
            ValuationContext valuationContext,
            UniversalScreenScore score,
            UniversalScreenRuleSet ruleSet
    ) {
        List<String> valuationFindings = new ArrayList<>(valuationContext.evidence());
        valuationFindings.add("PE TTM=" + plainOrMissing(valuationContext.rawPe()));
        valuationFindings.add("PB=" + plainOrMissing(valuationContext.rawPb()));
        valuationFindings.add("估值语境分=" + plain(score.valuationScore()));
        return List.of(
                new UniversalScreenTraceStep(
                        "QUOTE",
                        "行情合并",
                        "用全 A 行情池构建候选，并用实时行情复核价格与成交额。",
                        List.of(
                                "价格=" + plainOrMissing(quote.latestPrice()),
                                "涨跌幅=" + plainOrMissing(quote.changePercent()) + "%",
                                "成交额阈值 " + plain(ruleSet.minAmount())
                        ),
                        quote.sourceName(),
                        quote.quoteUrl()
                ),
                new UniversalScreenTraceStep(
                        "VALUATION",
                        "估值语境",
                        "PE/PB 只形成低权重参考语境，不作为资格门槛，也不直接等同安全。",
                        valuationFindings,
                        quote.sourceName(),
                        quote.quoteUrl()
                ),
                new UniversalScreenTraceStep(
                        "RISK",
                        "风险与动作纪律",
                        "通过资格过滤不等于立即买入，所有买入动作仍需证据和风控门禁。",
                        List.of("综合分=" + plain(score.finalScore()), "风险分=" + plain(score.riskScore())),
                        "系统风控",
                        null
                )
        );
    }

    private UniversalScreenCandidate rerank(UniversalScreenCandidate candidate, int rank) {
        return new UniversalScreenCandidate(
                rank,
                candidate.symbol(),
                candidate.name(),
                candidate.market(),
                candidate.industry(),
                candidate.latestPrice(),
                candidate.sourceName(),
                candidate.quoteUrl(),
                candidate.fetchedAt(),
                candidate.tradeDate(),
                candidate.marketTimestamp(),
                candidate.changePercent(),
                candidate.peTtm(),
                candidate.pbRatio(),
                candidate.amount(),
                candidate.valuationContext(),
                candidate.score(),
                candidate.bucket(),
                candidate.action(),
                candidate.actionLabel(),
                candidate.reason(),
                candidate.strengths(),
                candidate.risks(),
                candidate.dataGaps(),
                candidate.trace()
        );
    }

    private UniversalScreenRuleSet resolveRuleSet(UniversalScreenRequest request) {
        UniversalScreenRequest safe = request == null
                ? new UniversalScreenRequest(null, null, null, null, null, null, null, null, null)
                : request;
        UniversalScreenMode mode = UniversalScreenMode.fromExternal(safe.mode());
        boolean requestedSidewaysReview = safe.excludeSideways() == null || safe.excludeSideways();
        return new UniversalScreenRuleSet(
                Math.max(1, Math.min(safe.limit() == null ? DEFAULT_LIMIT : safe.limit(), MAX_LIMIT)),
                Math.max(50, Math.min(safe.scanLimit() == null ? DEFAULT_SCAN_LIMIT : safe.scanLimit(), MAX_SCAN_LIMIT)),
                RecommendationQuality.requiredAmount(positiveOrDefault(safe.minAmount(), DEFAULT_MIN_AMOUNT)),
                positiveOrDefault(safe.maxPe(), DEFAULT_MAX_PE),
                positiveOrDefault(safe.maxPb(), DEFAULT_MAX_PB),
                positiveOrDefault(safe.minFinancialScore(), DEFAULT_MIN_FINANCIAL_SCORE),
                mode.effectiveSidewaysReview(requestedSidewaysReview),
                safe.includeNorthExchange() == null || safe.includeNorthExchange(),
                mode.name()
        );
    }

    private UniversalScreenStageStats stats(String stage, String label, int inputCount, int passedCount) {
        return new UniversalScreenStageStats(
                stage,
                label,
                inputCount,
                passedCount,
                Math.max(0, inputCount - passedCount),
                0
        );
    }

    private UniversalScreenStageStats deferredStats(String stage, String label, int inputCount, int passedCount) {
        return new UniversalScreenStageStats(
                stage,
                label,
                inputCount,
                passedCount,
                0,
                Math.max(0, inputCount - passedCount)
        );
    }

    private String quoteNote(
            List<EastMoneyQuote> modeEligible,
            List<EastMoneyQuote> liquid,
            UniversalScreenCoverage coverage,
            UniversalScreenMode mode
    ) {
        String base = switch (mode) {
            case ALL -> "全市场模式使用实时行情复核，只核对证券资格和数据质量，不输出统一买入结论。";
            case VALUE -> "长线价值模式使用实时行情复核，PE/PB 仅作软估值语境；负 PE 可进入周期或反转研究，买入前仍需点时财务和风险证据。";
            case CYCLE -> "周期模式使用实时行情复核，要求周期行业和流动性，允许负 PE，买入前仍需供需周期证据。";
            case SHORT_TERM -> "短线模式使用实时行情复核，要求流动性并排除无有效突破的长期横盘，PE/PB 不作通用硬门槛。";
        };
        String coverageNote = coverage.complete()
                ? " 本轮已完成目标股票池覆盖 " + coverage.fetchedCount() + "/" + coverage.expectedCount() + "。"
                : " 本轮仅为部分覆盖 " + coverage.fetchedCount() + "/" + coverage.expectedCount()
                + "，缺失 " + coverage.missingCount() + " 只，不得把当前排名解释为完整全市场结论。";
        if (!modeEligible.isEmpty() && liquid.isEmpty() && modeEligible.stream().allMatch(this::hasZeroOrMissingAmount)) {
            return base + coverageNote + " 当前实时成交额为 0 或缺失，通常发生在盘前或行情源未形成当日成交额时；请在开盘后或尾盘重新计算。";
        }
        return base + coverageNote;
    }

    private boolean hasZeroOrMissingAmount(EastMoneyQuote quote) {
        return quote.amount() == null || quote.amount().compareTo(ZERO) <= 0;
    }

    private boolean isSupportedAshare(String symbol, boolean includeNorthExchange) {
        if (symbol.startsWith("0") || symbol.startsWith("3") || symbol.startsWith("6")) {
            return true;
        }
        return includeNorthExchange && (symbol.startsWith("4") || symbol.startsWith("8") || symbol.startsWith("92"));
    }

    private String bucket(EastMoneyQuote quote, UniversalScreenRuleSet ruleSet) {
        if (isCycleIndustry(quote.industry())) {
            return "CYCLE";
        }
        if (isTechIndustry(quote.industry())) {
            return "GROWTH";
        }
        if (isDefensiveIndustry(quote.industry())) {
            return "VALUE";
        }
        return "ALL";
    }

    private boolean isDefensiveIndustry(String industry) {
        return containsAny(industry, "银行", "保险", "电力", "公用", "高速", "铁路", "食品", "饮料", "家电", "乳品", "医药");
    }

    private boolean isTechIndustry(String industry) {
        return containsAny(industry, "半导体", "电子", "通信", "软件", "计算机", "光学", "元件", "自动化", "机器人");
    }

    private boolean isCycleIndustry(String industry) {
        return containsAny(industry, "农业", "养殖", "煤炭", "有色", "钢铁", "化工", "水泥", "建材", "航运", "猪", "电池");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(ZERO) > 0) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal firstPresent(BigDecimal primary, BigDecimal fallback) {
        return primary == null ? fallback : primary;
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value != null && value.compareTo(ZERO) > 0 ? value : fallback;
    }

    private Duration validTimeout(Duration timeout) {
        return timeout != null && !timeout.isZero() && !timeout.isNegative() ? timeout : DEFAULT_QUOTE_TIMEOUT;
    }

    private String firstText(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private BigDecimal weighted(BigDecimal score, String weight) {
        return score.multiply(new BigDecimal(weight));
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value.compareTo(ZERO) < 0) {
            return ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value.compareTo(new BigDecimal("100")) > 0) {
            return new BigDecimal("100.00");
        }
        return value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String plainOrMissing(BigDecimal value) {
        return value == null ? "缺失" : plain(value);
    }

    private String plain(BigDecimal value) {
        return value == null ? "缺失" : value.stripTrailingZeros().toPlainString();
    }

    private record StageProblem(String reason, List<String> evidence) {
    }

    private record ActionDecision(String action, String label, String reason) {
    }
}

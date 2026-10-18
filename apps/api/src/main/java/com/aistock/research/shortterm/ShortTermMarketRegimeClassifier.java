package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.AsharePriceLimitRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

@Component
public class ShortTermMarketRegimeClassifier {

    private static final BigDecimal MIN_EXECUTABLE_COVERAGE = new BigDecimal("0.95");

    public ShortTermMarketRegime classify(
            List<EastMoneyQuote> source,
            ShortTermCoverageSnapshot coverage,
            ShortTermMarketSentiment sentiment
    ) {
        return classify(source, coverage, sentiment, null);
    }

    /**
     * limitUpPulse 非空时用真实涨停池计数替代涨跌幅近似，其余判定口径不变；
     * 为空时行为与三参版本完全一致。
     */
    public ShortTermMarketRegime classify(
            List<EastMoneyQuote> source,
            ShortTermCoverageSnapshot coverage,
            ShortTermMarketSentiment sentiment,
            ShortTermLimitUpSentiment limitUpPulse
    ) {
        if (coverage == null || !coverage.executionReliable() || coverage.coverageRatio() == null
                || coverage.coverageRatio().compareTo(MIN_EXECUTABLE_COVERAGE) < 0) {
            return ShortTermMarketRegime.unavailable("全市场有效行情覆盖未通过95%，不推断市场状态");
        }
        List<EastMoneyQuote> quotes = source == null ? List.of() : source.stream()
                .filter(quote -> quote != null && quote.changePercent() != null)
                .toList();
        if (quotes.size() < 3) {
            return ShortTermMarketRegime.unavailable("有效涨跌幅样本不足3只");
        }
        int expectedReturnSamples = Math.max(3, (int) Math.ceil(coverage.fetchedCount() * 0.95d));
        if (quotes.size() < expectedReturnSamples) {
            return ShortTermMarketRegime.unavailable(
                    "全市场涨跌幅样本覆盖不足95%：" + quotes.size() + "/" + coverage.fetchedCount()
            );
        }
        List<BigDecimal> changes = quotes.stream()
                .map(EastMoneyQuote::changePercent)
                .sorted(Comparator.naturalOrder())
                .toList();
        long advancing = changes.stream().filter(value -> value.signum() > 0).count();
        int limitUp = limitUpPulse != null
                ? limitUpPulse.limitUpCount()
                : (int) quotes.stream()
                        .filter(quote -> AsharePriceLimitRule.isLimitUpLike(quote.symbol(), quote.changePercent()))
                        .count();
        int limitDown = limitUpPulse != null && limitUpPulse.limitDownCount() != null
                ? limitUpPulse.limitDownCount()
                : (int) quotes.stream()
                        .filter(quote -> AsharePriceLimitRule.isLimitDownLike(quote.symbol(), quote.changePercent()))
                        .count();
        BigDecimal breadth = percent(BigDecimal.valueOf(advancing), BigDecimal.valueOf(quotes.size()));
        BigDecimal median = median(changes);
        BigDecimal averageAbsolute = changes.stream()
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(changes.size()), 6, RoundingMode.HALF_UP);
        BigDecimal totalTurnover = quotes.stream()
                .map(EastMoneyQuote::amount)
                .filter(value -> value != null && value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal advancingTurnover = quotes.stream()
                .filter(quote -> quote.changePercent().signum() > 0)
                .map(EastMoneyQuote::amount)
                .filter(value -> value != null && value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long amountSamples = quotes.stream().filter(q -> q.amount() != null && q.amount().signum() >= 0).count();
        BigDecimal advancingTurnoverShare = amountSamples >= Math.ceil(quotes.size() * 0.95d) && totalTurnover.signum() > 0
                ? percent(advancingTurnover, totalTurnover)
                : null;
        BigDecimal limitUpRatio = percent(BigDecimal.valueOf(limitUp), BigDecimal.valueOf(quotes.size()));
        BigDecimal limitDownRatio = percent(BigDecimal.valueOf(limitDown), BigDecimal.valueOf(quotes.size()));

        String state;
        String label;
        String maxAction;
        String explanation;
        boolean sentimentRiskOff = sentiment != null && "极端退潮".equals(sentiment.phase());
        boolean weakBreadthWithMaterialLoss = breadth.compareTo(new BigDecimal("25")) <= 0
                && (median.compareTo(new BigDecimal("-1.50")) <= 0
                || averageAbsolute.compareTo(new BigDecimal("2.00")) >= 0);
        boolean turnoverRiskOff = advancingTurnoverShare != null
                && advancingTurnoverShare.compareTo(new BigDecimal("25")) <= 0
                && median.compareTo(new BigDecimal("-0.80")) <= 0;
        if (sentimentRiskOff || weakBreadthWithMaterialLoss || turnoverRiskOff
                || limitDownRatio.compareTo(new BigDecimal("10")) >= 0) {
            state = "RISK_OFF";
            label = "系统性退潮";
            maxAction = "NO_TRADE";
            explanation = "上涨广度与跌停扩散进入系统性风险区，个股强势不构成逆势买点。";
        } else if (averageAbsolute.compareTo(new BigDecimal("3.50")) > 0
                && (breadth.compareTo(new BigDecimal("75")) >= 0
                || limitUpRatio.compareTo(new BigDecimal("1.20")) >= 0)) {
            state = "CROWDED_VOLATILE";
            label = "拥挤高波动";
            maxAction = "LIGHT_TRIAL";
            explanation = "上涨共识较强但振幅与分化同步扩大，只允许轻仓验证，防止高潮次日反噬。";
        } else if (breadth.compareTo(new BigDecimal("60")) >= 0
                && median.compareTo(new BigDecimal("0.40")) >= 0
                && (limitUpRatio.compareTo(limitDownRatio) > 0
                || (limitUpRatio.signum() == 0 && limitDownRatio.signum() == 0))
                && advancingTurnoverShare != null
                && advancingTurnoverShare.compareTo(new BigDecimal("55")) >= 0
                && averageAbsolute.compareTo(new BigDecimal("3.50")) <= 0) {
            state = "TREND_EXPANSION";
            label = "有序趋势扩张";
            maxAction = "NORMAL";
            explanation = "市场上涨广度、收益中位数与涨跌停结构共振，允许按个股信号执行正常试错纪律。";
        } else {
            state = "REPAIR";
            label = "修复/混合";
            maxAction = "LIGHT_TRIAL";
            explanation = "市场尚未形成稳定趋势扩张，个股右侧信号最多轻仓试错，不作强加仓。";
        }
        List<String> dataGaps = new ArrayList<>();
        if (advancingTurnoverShare == null) {
            dataGaps.add("全市场成交额缺失，不能确认上涨侧资金参与度");
        }
        if (limitUpPulse != null && limitUpPulse.brokenCount() == null) {
            dataGaps.add("涨停池炸板数据缺失，炸板率未知");
        }
        if (limitUpPulse != null) {
            explanation = explanation + " " + limitUpPoolEvidence(limitUpPulse);
        }
        return new ShortTermMarketRegime(
                state,
                label,
                scale(breadth),
                scale(median),
                scale(averageAbsolute),
                scale(advancingTurnoverShare),
                scale(limitUpRatio),
                scale(limitDownRatio),
                quotes.size(),
                maxAction,
                explanation,
                dataGaps, null, limitUp, limitDown,
                limitUpPulse == null ? null : limitUpPulse.brokenCount(),
                limitUpPulse == null ? null : limitUpPulse.sealBreakRatioPercent(),
                limitUpPulse == null ? "PRICE_CHANGE_PROXY" : limitUpPulse.limitDownCount() == null ? "MIXED" : "MEASURED_POOL"
        );
    }

    /** Liquidity is an execution constraint, never an extra individual-stock score. */
    public ShortTermMarketRegime classify(List<EastMoneyQuote> source, ShortTermCoverageSnapshot coverage,
            ShortTermMarketSentiment sentiment, ShortTermLimitUpSentiment pulse, ShortTermMarketLiquidity liquidity) {
        ShortTermMarketRegime base = classify(source, coverage, sentiment, pulse);
        String state = base.state(), label = base.label(), action = base.maxAction();
        String explanation = base.explanation();
        List<String> gaps = new ArrayList<>(base.dataGaps());
        boolean comparable = liquidity != null && ("SAME_TIME".equals(liquidity.status()) || "CLOSE".equals(liquidity.status()))
                && liquidity.amountChangePercent() != null && liquidity.volumeChangePercent() != null;
        if (liquidity != null) gaps.addAll(liquidity.dataGaps());
        if (!base.blocksExecution()) {
            if (!comparable) {
                action = "LIGHT_TRIAL";
                explanation += " 同时段量额比较不足，最多轻仓，不将缺失值解释为缩量。";
                gaps.add("两市同一时段成交量/成交额暂不可比");
            } else if (liquidity.amountChangePercent().compareTo(new BigDecimal("10")) >= 0
                    && base.breadthPercent().compareTo(new BigDecimal("35")) <= 0
                    && base.medianChangePercent().compareTo(new BigDecimal("-0.80")) <= 0) {
                state = "RISK_OFF"; label = "放量下跌"; action = "NO_TRADE";
                explanation += " 同刻成交额增加至少10%，上涨家数不超过35%且涨跌幅中位数不高于-0.8%，暂停新仓。";
            } else if (liquidity.amountChangePercent().compareTo(new BigDecimal("-15")) <= 0
                    || liquidity.volumeChangePercent().compareTo(new BigDecimal("-15")) <= 0) {
                action = "LIGHT_TRIAL";
                if ("TREND_EXPANSION".equals(state)) { state = "LIQUIDITY_CONTRACTION"; label = "缩量分歧"; }
                explanation += " 同刻成交量或成交额下降至少15%，参与度不足，最多轻仓。";
            }
            if (!"NO_TRADE".equals(action) && pulse != null && pulse.brokenCount() != null
                    && pulse.limitUpCount() + pulse.brokenCount() >= 10
                    && pulse.sealBreakRatioPercent() != null
                    && pulse.sealBreakRatioPercent().compareTo(new BigDecimal("45")) >= 0) {
                action = "LIGHT_TRIAL";
                explanation += " 实测炸板率达到45%，接力分歧较大，最多轻仓。";
            }
        }
        if ("LIGHT_TRIAL".equals(action)) explanation += " 单只计划上限为短线资金的20%，已有更严上限继续保留。";
        return new ShortTermMarketRegime(state, label, base.breadthPercent(), base.medianChangePercent(),
                base.averageAbsoluteChangePercent(), base.advancingTurnoverSharePercent(), base.limitUpRatioPercent(),
                base.limitDownRatioPercent(), base.sampleCount(), action, explanation, gaps.stream().distinct().toList(),
                liquidity, base.limitUpCount(), base.limitDownCount(), base.brokenCount(),
                base.sealBreakRatioPercent(), base.limitCountSource());
    }

    private String limitUpPoolEvidence(ShortTermLimitUpSentiment pulse) {
        StringBuilder text = new StringBuilder("涨停池实测：涨停 ")
                .append(pulse.limitUpCount())
                .append(" 家");
        if (pulse.brokenCount() != null) {
            text.append("、炸板 ").append(pulse.brokenCount()).append(" 家");
            if (pulse.sealBreakRatioPercent() != null) {
                text.append("（炸板率 ").append(pulse.sealBreakRatioPercent()).append("%）");
            }
        }
        if (pulse.limitDownCount() != null) {
            text.append("、跌停 ").append(pulse.limitDownCount()).append(" 家");
        }
        text.append("、最高 ")
                .append(pulse.maxConsecutiveBoards())
                .append(" 连板（东方财富涨停池，替代涨跌幅近似）。");
        return text.toString();
    }

    private BigDecimal median(List<BigDecimal> values) {
        int size = values.size();
        if (size % 2 == 1) {
            return values.get(size / 2);
        }
        return values.get(size / 2 - 1).add(values.get(size / 2))
                .divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(new BigDecimal("100"))
                .divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}

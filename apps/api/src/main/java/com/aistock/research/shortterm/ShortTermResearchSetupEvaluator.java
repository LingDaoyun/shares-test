package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic, initial research settings for a 1–2 trading-day review horizon.
 * These thresholds are our quantitative approximations, not an expert's original
 * system or a validated performance claim. A single 5/20 true-range contraction is
 * deliberately called 收缩突破, never a complete Minervini VCP pattern.
 *
 * Weights: trend 25%, measured cross-sectional RS 20%, setup 35%, volume/price 20%.
 * At least 40 valid completed bars are needed to score; 61 prior bars establish
 * both MA20/MA60 and their non-declining one-session slopes before a trigger.
 * Trend extension is bounded by 2.5 prior ATR14 and the configured MA20 distance
 * (8% default). Breakout: prior TR5/TR20 <=0.80, above prior 20-bar high by at
 * most min(3%, 1.5 ATR); volume 0.8–3.0x to observe, >=1.2x to confirm.
 * Pullback: 2–5 consecutive declining completed closes after an established trend,
 * retreat volume <=0.85 of the preceding 20-day average, touch within 0.5 ATR
 * of MA10/MA20 without losing MA20 by >0.5 ATR, then reclaim MA10/prior-day high;
 * recovery <=2 ATR above MA20, volume 0.9–3.0x (>=1.0x for confirmation).
 * Confirmation additionally requires a completed cutoff-day bar, trusted data,
 * measured RS >=60, and the family-specific conditions. Intraday quote price and
 * provider realtime volume ratio can identify WATCH only. No daily-derived VWAP.
 */
@Component
public class ShortTermResearchSetupEvaluator {
    public static final String RULE_VERSION = "short-term-research-v7-market-context";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final TradingClockService tradingCalendar;

    public ShortTermResearchSetupEvaluator() {
        this(new TradingClockService());
    }

    @Autowired
    public ShortTermResearchSetupEvaluator(TradingClockService tradingCalendar) {
        this.tradingCalendar = java.util.Objects.requireNonNull(tradingCalendar, "tradingCalendar");
    }

    public ShortTermResearchSetup evaluate(List<EastMoneyKLine> source, EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical, ShortTermVolatilityQuality volatility,
            ShortTermRelativeStrength relativeStrength, ShortTermRuleSet rules) {
        List<String> evidence = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (quote == null || quote.tradeDate() == null || !positive(quote.latestPrice())) {
            gaps.add("报价交易日或有效价格缺失，拒绝无界历史计算");
            return result(Family.none(), null, null, null, "UNAVAILABLE", false, false, evidence, risks, gaps);
        }
        boolean sessionClosed = sessionClosed(quote);
        History history = history(source, quote, sessionClosed, gaps);
        List<EastMoneyKLine> rows = history.rows();
        EastMoneyKLine last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        LocalDate previousTradingDate = tradingCalendar.previousTradingDay(quote.tradeDate());
        boolean verifiedCalendar = tradingCalendar.verifiedTradingDayAfter(previousTradingDate, 1)
                .filter(quote.tradeDate()::equals).isPresent();
        LocalDate expectedCompletedDate = sessionClosed ? quote.tradeDate() : previousTradingDate;
        boolean currentHistory = verifiedCalendar && last != null && expectedCompletedDate.equals(last.tradeDate());
        if (!verifiedCalendar) {
            gaps.add("交易日历未核实，不能确认最近已完成交易日，禁止确认和执行");
        } else if (!currentHistory) {
            gaps.add("日线未更新至应有的最近已完成交易日 " + expectedCompletedDate
                    + "（最新为 " + (last == null ? "缺失" : last.tradeDate()) + "），旧结构仅作观察，禁止确认和执行");
        }
        if (rows.size() < 40) {
            gaps.add("有效已完成OHLCV日线不足40根，研究形态不参与排序");
            return result(Family.none(), null, null, null, "UNAVAILABLE", false, currentHistory, evidence, risks, gaps);
        }
        boolean completed = sessionClosed && quote.tradeDate().equals(last.tradeDate());
        List<EastMoneyKLine> prior = completed ? rows.subList(0, rows.size() - 1) : rows;
        BigDecimal price = completed ? last.close() : quote.latestPrice();
        BigDecimal atr = atr(prior, 14);
        BigDecimal ma20 = averageClose(prior, 20, 0);
        Trend trend = trend(prior, price, atr, rules);
        if (prior.size() < 61) gaps.add("已完成历史不足61根，无法确认MA60方向");
        if (!completed) {
            gaps.add("截止日收盘K线未确认，仅使用即时价格与供应商实时量比观察");
            evidence.add("盘中量价为报价代理值，不代表完整日线或真实VWAP");
        }
        if (quote.marketTimestamp() == null
                || !quote.marketTimestamp().atZone(SHANGHAI).toLocalDate().equals(quote.tradeDate())) {
            gaps.add("市场时间戳缺失或与报价交易日不一致，禁止收盘确认");
        }
        BigDecimal volumeRatio = completed
                ? ratio(last.volume(), averageVolume(prior, 20)) : quote.realtimeVolumeRatio();
        if (!positive(volumeRatio)) {
            volumeRatio = null;
            gaps.add("有效量比缺失，量能不补值");
        }
        BigDecimal relativeScore = relativeStrength == null || relativeStrength.compositeScore() == null
                || relativeStrength.marketSampleCount() < 3 ? null : clamp(relativeStrength.compositeScore());
        if (relativeScore == null) gaps.add("横截面相对强度缺失，20%权重贡献记0且不重新分配");
        else evidence.add("横截面相对强度 " + scale(relativeScore) + "，市场样本 " + relativeStrength.marketSampleCount());
        if (relativeStrength != null) gaps.addAll(relativeStrength.dataGaps());

        Family family = Family.none();
        if (trend.qualified() && positive(atr)) {
            family = breakout(prior, price, atr, volumeRatio, completed, last, evidence);
            if (!family.matched()) family = pullback(prior, price, atr, volumeRatio, completed, rules, evidence);
            if (!family.matched()) family = support(technical, quote, price, atr, completed, evidence, gaps);
            if (!family.matched()) family = goldenCross(technical, quote, rows, last, price, ma20, atr,
                    volumeRatio, completed, evidence);
        } else risks.add("MA20/MA60趋势或ATR延伸条件未通过，不能以普通上涨替代明确形态");
        if (family.matched()) evidence.add("趋势：价格在MA20/MA60上方，MA20不低于MA60且两均线方向不下降");
        else risks.add("未匹配收缩突破、趋势回踩、支撑反转或近期金叉动量");
        if (relativeScore != null && relativeScore.compareTo(bd("60")) < 0)
            risks.add("横截面相对强度低于60，仅保留观察");
        if (volatility != null && "OVEREXTENDED".equals(volatility.state()))
            risks.add("原波动率评估提示过度拉开，请结合本评分的点时ATR边界复核");
        BigDecimal volumePriceScore = volumePrice(volumeRatio, completed, last, price, prior);
        boolean confirmed = family.confirmable() && completed && history.trusted() && currentHistory
                && relativeScore != null && relativeScore.compareTo(bd("60")) >= 0;
        // Readiness authorizes only subsequent tail review; execution remains the service's decision.
        boolean executionReady = family.matched() && trend.qualified() && history.trusted() && currentHistory
                && quote.marketTimestamp() != null
                && quote.marketTimestamp().atZone(SHANGHAI).toLocalDate().equals(quote.tradeDate())
                && relativeScore != null && relativeScore.compareTo(bd("60")) >= 0
                && positive(family.invalidation()) && positive(scale(family.invalidation()))
                && scale(family.trigger()).compareTo(scale(family.invalidation())) > 0
                && executionVolumeReady(family, volumeRatio, technical);
        String state = family.matched() ? confirmed ? "CONFIRMED" : "WATCH" : "UNAVAILABLE";
        risks.add("初始研究参数尚未验证收益，仍需执行时窗、行情新鲜度与真实尾盘量价门槛");
        return result(family, trend.score(), relativeScore, volumePriceScore, state, executionReady, currentHistory, evidence, risks, gaps);
    }

    /** Structural admission only; deliberately independent from RS and execution authorization. */
    public boolean qualifiesWithoutGoldenCross(List<EastMoneyKLine> rows, EastMoneyQuote quote,
            ShortTermTechnicalSnapshot technical, ShortTermVolatilityQuality volatility, ShortTermRuleSet rules) {
        return evaluate(rows, quote, technical, volatility, null, rules).matched();
    }

    private boolean executionVolumeReady(Family family, BigDecimal volumeRatio, ShortTermTechnicalSnapshot technical) {
        return switch (family.type()) {
            case "CONTRACTION_BREAKOUT" -> between(volumeRatio, "1.2", "3.0");
            case "TREND_PULLBACK" -> between(volumeRatio, "1.0", "3.0");
            case "SUPPORT_REVERSAL" -> technical != null && technical.supportReversal() != null
                    && technical.supportReversal().volumeQualified() && technical.supportReversal().turnoverQualified()
                    && technical.supportReversal().dataGaps().isEmpty();
            case "GOLDEN_CROSS_MOMENTUM" -> family.confirmable() && between(volumeRatio, "1.2", "3.0");
            default -> false;
        };
    }

    private Family breakout(List<EastMoneyKLine> prior, BigDecimal price, BigDecimal atr,
            BigDecimal volumeRatio, boolean completed, EastMoneyKLine current, List<String> evidence) {
        BigDecimal pivot = high(prior, 20);
        BigDecimal contraction = ratio(atr(prior, 5), atr(prior, 20));
        BigDecimal extension = price.subtract(pivot);
        if (contraction == null || contraction.compareTo(bd("0.80")) > 0
                || extension.signum() < 0 || extension.compareTo(atr.multiply(bd("1.5"))) > 0
                || extension.compareTo(pivot.multiply(bd("0.03"))) > 0
                || !between(volumeRatio, "0.8", "3.0")) return Family.none();
        evidence.add("收缩突破量化近似：前5/20日真实波幅比 " + scale(contraction)
                + "，越过前20日高点 " + scale(pivot) + "，量比 " + scale(volumeRatio));
        boolean strongClose = completed && closeLocation(current).compareTo(bd("0.65")) >= 0;
        return new Family("CONTRACTION_BREAKOUT", "收缩突破", bd(strongClose ? "100" : "90"),
                pivot, pivot.subtract(atr).max(low(prior, 5)),
                strongClose && volumeRatio.compareTo(bd("1.2")) >= 0);
    }

    private Family pullback(List<EastMoneyKLine> prior, BigDecimal price, BigDecimal atr,
            BigDecimal volumeRatio, boolean completed, ShortTermRuleSet rules, List<String> evidence) {
        int end = prior.size() - 1;
        int retreat = 0;
        while (end - retreat > 0 && retreat < 6
                && prior.get(end - retreat).close().compareTo(prior.get(end - retreat - 1).close()) < 0) retreat++;
        if (retreat < 2 || retreat > 5 || prior.size() - retreat < 61) return Family.none();
        List<EastMoneyKLine> before = prior.subList(0, prior.size() - retreat);
        if (!trend(before, before.get(before.size() - 1).close(), atr(before, 14), rules).qualified())
            return Family.none();
        BigDecimal retreatRatio = ratio(averageVolume(prior, retreat), averageVolume(before, 20));
        BigDecimal ma10 = averageClose(prior, 10, 0);
        BigDecimal ma20 = averageClose(prior, 20, 0);
        BigDecimal retreatLow = low(prior, retreat);
        BigDecimal trigger = ma10.max(prior.get(end).high());
        boolean touch = retreatLow.subtract(ma10).abs().min(retreatLow.subtract(ma20).abs())
                .compareTo(atr.multiply(bd("0.5"))) <= 0;
        if (retreatRatio.compareTo(bd("0.85")) > 0 || !touch
                || retreatLow.compareTo(ma20.subtract(atr.multiply(bd("0.5")))) < 0
                || price.compareTo(trigger) < 0 || price.subtract(ma20).compareTo(atr.multiply(bd("2"))) > 0
                || !between(volumeRatio, "0.9", "3.0")) return Family.none();
        evidence.add("趋势回踩量化近似：连续 " + retreat + " 根缩量回落，回落量比 " + scale(retreatRatio)
                + "，触及MA10/MA20后重新站上回升触发价 " + scale(trigger));
        return new Family("TREND_PULLBACK", "趋势回踩", bd("95"), trigger,
                retreatLow.subtract(atr.multiply(bd("0.25"))), completed && volumeRatio.compareTo(BigDecimal.ONE) >= 0);
    }

    private Family support(ShortTermTechnicalSnapshot technical, EastMoneyQuote quote, BigDecimal price,
            BigDecimal atr, boolean completed, List<String> evidence, List<String> gaps) {
        if (!technicalAtCutoff(technical, quote)) return Family.none();
        ShortTermSupportReversalSignal support = technical.supportReversal();
        if (support == null || (!support.confirmed() && !support.watchLayer())
                || !support.supportReclaimed() || !support.trendQualified() || !positive(support.supportPrice())
                || price.compareTo(support.supportPrice()) < 0
                || price.subtract(support.supportPrice()).compareTo(atr.multiply(bd("2"))) > 0) return Family.none();
        evidence.add("沿用长下影承接证据，支撑 " + scale(support.supportPrice()) + " 已收复");
        evidence.addAll(support.reasons());
        gaps.addAll(support.dataGaps());
        return new Family("SUPPORT_REVERSAL", "支撑反转", support.score() == null ? bd("65") : clamp(support.score()),
                support.supportPrice(), support.supportPrice().subtract(atr.multiply(bd("0.5"))),
                completed && support.confirmed() && !support.provisional()
                        && support.volumeQualified() && support.turnoverQualified() && support.dataGaps().isEmpty());
    }

    private Family goldenCross(ShortTermTechnicalSnapshot technical, EastMoneyQuote quote, List<EastMoneyKLine> completedRows, EastMoneyKLine last,
            BigDecimal price, BigDecimal ma20, BigDecimal atr, BigDecimal volumeRatio,
            boolean completed, List<String> evidence) {
        if (!technicalAtCutoff(technical, quote) || technical.goldenCross() == null) return Family.none();
        ShortTermGoldenCrossSnapshot cross = technical.goldenCross();
        if (!cross.confirmedRecent() && !cross.watchLayer()) return Family.none();
        if (cross.crossDate() != null && cross.crossDate().isAfter(quote.tradeDate())) return Family.none();
        boolean completedRecentCross = cross.crossDate() != null
                && completedRows.stream().anyMatch(row -> row.tradeDate().equals(cross.crossDate()))
                && completedRows.stream().filter(row -> row.tradeDate().isAfter(cross.crossDate())).count() <= 3;
        evidence.add("近期金叉作为备用动量形态，需量能与完整日线确认");
        return new Family("GOLDEN_CROSS_MOMENTUM", "金叉动量", bd("75"), ma20,
                ma20.subtract(atr.multiply(bd("0.5"))), completed && cross.confirmedRecent() && completedRecentCross
                && cross.crossDate() != null && !cross.crossDate().isAfter(last.tradeDate())
                && between(volumeRatio, "1.2", "3.0") && price.compareTo(ma20) >= 0);
    }

    private Trend trend(List<EastMoneyKLine> prior, BigDecimal price, BigDecimal atr, ShortTermRuleSet rules) {
        BigDecimal ma20 = averageClose(prior, 20, 0), old20 = averageClose(prior, 20, 1);
        BigDecimal ma60 = averageClose(prior, 60, 0), old60 = averageClose(prior, 60, 1);
        int score = 0;
        if (ma20 != null && price.compareTo(ma20) >= 0) score += 20;
        if (ma20 != null && ma60 != null && ma20.compareTo(ma60) >= 0) score += 20;
        if (old20 != null && ma20.compareTo(old20) >= 0) score += 25;
        if (old60 != null && ma60.compareTo(old60) >= 0) score += 20;
        if (ma60 != null && price.compareTo(ma60) >= 0) score += 15;
        BigDecimal maxDistance = rules != null && positive(rules.maxDistanceToMa20Percent())
                ? rules.maxDistanceToMa20Percent() : bd("8");
        boolean bounded = positive(atr) && ma20 != null
                && price.subtract(ma20).compareTo(atr.multiply(bd("2.5"))) <= 0
                && price.subtract(ma20).multiply(HUNDRED).compareTo(ma20.multiply(maxDistance)) <= 0;
        return new Trend(BigDecimal.valueOf(score), score == 100 && bounded);
    }

    private History history(List<EastMoneyKLine> source, EastMoneyQuote quote, boolean closed, List<String> gaps) {
        Map<LocalDate, EastMoneyKLine> byDate = new TreeMap<>();
        Set<LocalDate> conflicts = new HashSet<>();
        boolean trusted = true;
        if (source != null) for (EastMoneyKLine row : source) {
            if (row == null || row.tradeDate() == null) { trusted = false; continue; }
            if (row.tradeDate().isAfter(quote.tradeDate())
                    || (!closed && row.tradeDate().equals(quote.tradeDate()))) continue;
            if (!valid(row) || (quote.symbol() != null && !quote.symbol().equals(row.symbol()))) {
                trusted = false;
                continue;
            }
            EastMoneyKLine previous = byDate.putIfAbsent(row.tradeDate(), row);
            if (previous != null && !sameBar(previous, row)) conflicts.add(row.tradeDate());
        }
        if (!conflicts.isEmpty()) {
            conflicts.forEach(byDate::remove);
            trusted = false;
        }
        if (!trusted) gaps.add("历史存在无效OHLCV、日期缺失或同日冲突，已排除且禁止确认");
        return new History(List.copyOf(byDate.values()), trusted);
    }

    private boolean valid(EastMoneyKLine row) {
        return positive(row.open()) && positive(row.close()) && positive(row.high()) && positive(row.low())
                && positive(row.volume()) && row.high().compareTo(row.open().max(row.close())) >= 0
                && row.low().compareTo(row.open().min(row.close())) <= 0 && row.high().compareTo(row.low()) >= 0;
    }

    private boolean sameBar(EastMoneyKLine a, EastMoneyKLine b) {
        return a.open().compareTo(b.open()) == 0 && a.close().compareTo(b.close()) == 0
                && a.high().compareTo(b.high()) == 0 && a.low().compareTo(b.low()) == 0
                && a.volume().compareTo(b.volume()) == 0;
    }

    private boolean sessionClosed(EastMoneyQuote quote) {
        if (quote.marketTimestamp() == null) return false;
        var marketTime = quote.marketTimestamp().atZone(SHANGHAI);
        return marketTime.toLocalDate().equals(quote.tradeDate())
                && !marketTime.toLocalTime().isBefore(LocalTime.of(15, 0));
    }

    private boolean technicalAtCutoff(ShortTermTechnicalSnapshot technical, EastMoneyQuote quote) {
        return technical != null && quote.tradeDate().equals(technical.tradeDate());
    }

    private BigDecimal volumePrice(BigDecimal volumeRatio, boolean completed, EastMoneyKLine current,
            BigDecimal price, List<EastMoneyKLine> prior) {
        BigDecimal result = between(volumeRatio, "1.0", "2.5") ? bd("60")
                : between(volumeRatio, "0.8", "3.0") ? bd("30") : ZERO;
        boolean priceStrength = completed ? closeLocation(current).compareTo(bd("0.65")) >= 0
                : price.compareTo(prior.get(prior.size() - 1).close()) >= 0;
        return result.add(priceStrength ? bd("40") : ZERO);
    }

    private BigDecimal closeLocation(EastMoneyKLine row) {
        BigDecimal range = row.high().subtract(row.low());
        return range.signum() <= 0 ? ZERO : ratio(row.close().subtract(row.low()), range);
    }

    private BigDecimal averageClose(List<EastMoneyKLine> rows, int length, int offset) {
        if (rows.size() < length + offset) return null;
        BigDecimal sum = ZERO;
        for (int i = rows.size() - length - offset; i < rows.size() - offset; i++) sum = sum.add(rows.get(i).close());
        return sum.divide(BigDecimal.valueOf(length), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal averageVolume(List<EastMoneyKLine> rows, int length) {
        if (rows.size() < length) return null;
        BigDecimal sum = ZERO;
        for (int i = rows.size() - length; i < rows.size(); i++) sum = sum.add(rows.get(i).volume());
        return sum.divide(BigDecimal.valueOf(length), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal atr(List<EastMoneyKLine> rows, int length) {
        if (rows.size() < length + 1) return null;
        BigDecimal sum = ZERO;
        for (int i = rows.size() - length; i < rows.size(); i++) {
            EastMoneyKLine row = rows.get(i);
            BigDecimal previous = rows.get(i - 1).close();
            sum = sum.add(row.high().subtract(row.low()).max(row.high().subtract(previous).abs())
                    .max(row.low().subtract(previous).abs()));
        }
        return sum.divide(BigDecimal.valueOf(length), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal high(List<EastMoneyKLine> rows, int length) {
        return rows.subList(rows.size() - length, rows.size()).stream().map(EastMoneyKLine::high)
                .max(BigDecimal::compareTo).orElseThrow();
    }

    private BigDecimal low(List<EastMoneyKLine> rows, int length) {
        return rows.subList(rows.size() - length, rows.size()).stream().map(EastMoneyKLine::low)
                .min(BigDecimal::compareTo).orElseThrow();
    }

    private ShortTermResearchSetup result(Family family, BigDecimal trend, BigDecimal rs, BigDecimal volumePrice,
            String state, boolean executionReady, boolean currentHistory, List<String> evidence, List<String> risks, List<String> gaps) {
        BigDecimal tc = contribution(trend, "0.25"), rc = contribution(rs, "0.20"),
                sc = contribution(family.score(), "0.35"), vc = contribution(volumePrice, "0.20");
        return new ShortTermResearchSetup(RULE_VERSION, family.type(), family.label(), state,
                tc.add(rc).add(sc).add(vc), scale(trend), scale(rs), scale(family.score()), scale(volumePrice),
                tc, rc, sc, vc, scale(family.trigger()), scale(family.invalidation()),
                evidence.stream().distinct().toList(), risks.stream().distinct().toList(), gaps.stream().distinct().toList(), executionReady,
                currentHistory);
    }

    private BigDecimal contribution(BigDecimal score, String weight) { return scale(score == null ? ZERO : clamp(score).multiply(bd(weight))); }
    private BigDecimal clamp(BigDecimal value) { return value.max(ZERO).min(HUNDRED); }
    private BigDecimal scale(BigDecimal value) { return value == null ? null : value.setScale(2, RoundingMode.HALF_UP); }
    private boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private BigDecimal ratio(BigDecimal a, BigDecimal b) { return a == null || !positive(b) ? null : a.divide(b, 8, RoundingMode.HALF_UP); }
    private boolean between(BigDecimal value, String min, String max) { return value != null && value.compareTo(bd(min)) >= 0 && value.compareTo(bd(max)) <= 0; }
    private BigDecimal bd(String value) { return new BigDecimal(value); }

    private record History(List<EastMoneyKLine> rows, boolean trusted) {}
    private record Trend(BigDecimal score, boolean qualified) {}
    private record Family(String type, String label, BigDecimal score, BigDecimal trigger,
                          BigDecimal invalidation, boolean confirmable) {
        boolean matched() { return !"UNAVAILABLE".equals(type); }
        static Family none() { return new Family("UNAVAILABLE", "研究形态待确认", ZERO, null, null, false); }
    }
}

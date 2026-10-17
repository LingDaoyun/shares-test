package com.aistock.research.fiveday;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.AsharePriceLimitRule;
import com.aistock.research.trading.TradingClockService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aistock.research.fiveday.FiveDayModels.*;

/** Daily-bar scenarios only. No return in this class is evidence of an actual fill. */
public final class FiveDayOutcomeCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final TradingClockService calendar;

    public FiveDayOutcomeCalculator(TradingClockService calendar) {
        this.calendar = calendar;
    }

    public Outcome evaluate(Snapshot snapshot, Candidate candidate, List<EastMoneyKLine> source, LocalDate asOf) {
        Rules rules = snapshot.rules() == null ? Rules.defaults() : snapshot.rules();
        LocalDate signalDate = snapshot.signalDate();
        LocalDate entryDate = calendar.verifiedTradingDayAfter(signalDate, 1).orElse(null);
        LocalDate targetDate = calendar.verifiedTradingDayAfter(entryDate, rules.holdingDays() - 1).orElse(null);
        if (entryDate == null || targetDate == null || signalDate == null || calendar.isMarketClosedDay(signalDate)
                || rules.holdingDays() < 2) {
            return unentered("CALENDAR_UNAVAILABLE", entryDate, targetDate, "缺少覆盖整个持有周期的已核实市场交易日历");
        }
        if (asOf == null || asOf.isBefore(entryDate)) {
            return unentered("PENDING_ENTRY", entryDate, targetDate, "尚未取得唯一入场交易日的完整日K");
        }
        Map<LocalDate, EastMoneyKLine> bars = new LinkedHashMap<>();
        for (EastMoneyKLine bar : source == null ? List.<EastMoneyKLine>of() : source) {
            if (bar != null && candidate.symbol().equals(bar.symbol()) && bar.tradeDate() != null
                    && !bar.tradeDate().isAfter(asOf)) bars.put(bar.tradeDate(), bar);
        }
        EastMoneyKLine anchor = bars.get(signalDate);
        if (anchor == null || !positive(anchor.close()) || !positive(candidate.signalClose())
                || !positive(candidate.stopPrice()) || !positive(candidate.maxEntryPrice())) {
            return unentered("MISSING_DATA", entryDate, targetDate,
                    "信号日同一前复权序列锚点或固化价格边界缺失，不能跨复权基准比较");
        }
        BigDecimal factor = anchor.close().divide(candidate.signalClose(), 12, RoundingMode.HALF_UP);
        BigDecimal stop = candidate.stopPrice().multiply(factor);
        BigDecimal maxEntry = candidate.maxEntryPrice().multiply(factor);
        Quote entry = quote(candidate, entryDate, true, true, bars);
        if ("MISSING_DATA".equals(entry.status())) {
            return unentered("MISSING_DATA", entryDate, targetDate, "唯一入场日缺少开盘、前收盘或成交量，不能换日追买");
        }
        if (!"AVAILABLE".equals(entry.status())) {
            return unentered("ENTRY_NOT_FILLED", entryDate, targetDate, "唯一入场日开盘涨停附近或无成交量，不假设买入");
        }
        if (entry.price().compareTo(maxEntry) > 0 || entry.price().compareTo(stop) <= 0) {
            return unentered("ENTRY_NOT_FILLED", entryDate, targetDate, "唯一入场日开盘超过上限或低于、等于失效位，不换日追买");
        }
        BigDecimal entryPrice = entry.price();
        Exit fixed = fixedExit(candidate, targetDate, asOf, bars);
        Exit risk = riskExit(candidate, entryDate, targetDate, asOf, stop, bars, fixed);
        BigDecimal fixedGross = fixed.price() == null ? null : percent(fixed.price(), entryPrice);
        List<String> details = new ArrayList<>();
        details.add("日线情景估算，不是实际成交；买入日计第1天，第" + rules.holdingDays() + "个市场交易日计划退出。");
        details.add("入场只使用指定交易日开盘；按板块幅度的95%判定接近涨跌停：买入在涨停附近、卖出在跌停附近不能确认成交，零成交量始终不能确认成交。其余仅为日线情景假设。");
        details.add("QFQ同序列信号日锚点系数=" + factor.stripTrailingZeros().toPlainString()
                + "；失效位及入场上限已同比缩放，展示价格使用该序列口径。");
        details.add("两种收益均扣往返" + rules.roundTripCostBps().stripTrailingZeros().toPlainString()
                + "bps研究成本假设，包含费用及滑点，不是真实账单。");
        details.add("固定模型：" + fixed.detail());
        details.add("风险模型：" + risk.detail());
        details.add("最大不利幅度仅为入场日至原计划退出日的完整日线最低价观察；缺任一日则留空，不表示可成交止损。");
        return new Outcome(fixed.status(), risk.status(), entryDate, price(entryPrice), targetDate,
                fixed.date(), price(fixed.price()), fixedGross, net(fixed.price(), entryPrice, rules),
                risk.date(), price(risk.price()), net(risk.price(), entryPrice, rules),
                adverse(entryDate, targetDate, asOf, entryPrice, bars), List.copyOf(details));
    }

    private Exit fixedExit(Candidate candidate, LocalDate target, LocalDate asOf, Map<LocalDate, EastMoneyKLine> bars) {
        if (asOf.isBefore(target)) return exit("PENDING_EXIT", "原定第5日尚未收盘");
        Quote closing = quote(candidate, target, false, false, bars);
        if ("MISSING_DATA".equals(closing.status())) {
            return exit("MISSING_DATA", "原计划退出日缺少收盘、前收盘或成交量，不以之后第5根有效K线替代");
        }
        if ("AVAILABLE".equals(closing.status())) {
            return new Exit("COMPLETED", target, closing.price(), "按原定第5个交易日收盘价估算");
        }
        LocalDate date = calendar.verifiedTradingDayAfter(target, 1).orElse(null);
        while (date != null && !date.isAfter(asOf)) {
            Quote opening = quote(candidate, date, true, false, bars);
            if ("MISSING_DATA".equals(opening.status())) {
                return exit("MISSING_DATA", "原计划日不能确认卖出，延期至" + date + "时缺少开盘或前收盘数据，退出收益留空");
            }
            if ("AVAILABLE".equals(opening.status())) {
                return new Exit("COMPLETED_DEFERRED", date, opening.price(),
                        "原计划日不能确认卖出，按后续市场日" + date + "开盘情景延期退出；原计划日期不变");
            }
            date = calendar.verifiedTradingDayAfter(date, 1).orElse(null);
        }
        if (date == null) return exit("CALENDAR_UNAVAILABLE", "延期退出超出已核实日历范围，退出收益留空");
        return exit("EXIT_DEFERRED", "原计划日及已知后续开盘不能确认卖出，仍未退出，收益留空");
    }

    private Exit riskExit(Candidate candidate, LocalDate entry, LocalDate target, LocalDate asOf,
                          BigDecimal stop, Map<LocalDate, EastMoneyKLine> bars, Exit fixed) {
        LocalDate date = entry;
        while (date != null && date.isBefore(target) && !date.isAfter(asOf)) {
            EastMoneyKLine bar = bars.get(date);
            if (bar == null || !positive(bar.close()) || !positive(bar.volume())) {
                return exit("MISSING_DATA", date + "缺少可用收盘，不能事后判断此前是否已经触发退出");
            }
            if (bar.close().compareTo(stop) < 0) {
                return exitAfterBreach(candidate, date, target, asOf, bars, fixed);
            }
            date = calendar.verifiedTradingDayAfter(date, 1).orElse(null);
        }
        if (date == null) return exit("CALENDAR_UNAVAILABLE", "风险观察跨出已核实日历范围");
        return new Exit(fixed.status(), fixed.date(), fixed.price(),
                "此前完整收盘未触发失效位；" + fixed.detail());
    }

    private Exit exitAfterBreach(Candidate candidate, LocalDate breachDate, LocalDate target,
                                 LocalDate asOf, Map<LocalDate, EastMoneyKLine> bars, Exit fixed) {
        LocalDate date = calendar.verifiedTradingDayAfter(breachDate, 1).orElse(null);
        boolean deferred = false;
        while (date != null && !date.isAfter(target) && !date.isAfter(asOf)) {
            Quote opening = quote(candidate, date, true, false, bars);
            if ("MISSING_DATA".equals(opening.status())) {
                return exit("MISSING_DATA", breachDate + "收盘跌破失效位，但" + date + "开盘数据缺失，不能推定退出");
            }
            if ("AVAILABLE".equals(opening.status())) {
                return new Exit(deferred ? "COMPLETED_DEFERRED" : "COMPLETED", date, opening.price(),
                        breachDate + "收盘跌破失效位，遵守T+1，按" + date + "可确认的开盘情景退出");
            }
            deferred = true;
            if (date.equals(target)) break;
            date = calendar.verifiedTradingDayAfter(date, 1).orElse(null);
        }
        if (date == null) return exit("CALENDAR_UNAVAILABLE", "风险退出超出已核实日历范围，收益留空");
        if (!asOf.isBefore(target)) {
            return new Exit(fixed.status(), fixed.date(), fixed.price(),
                    breachDate + "收盘触发后，截至第5日开盘仍不能确认卖出，转原定第5日收盘及其延期规则；" + fixed.detail());
        }
        return exit(deferred ? "EXIT_DEFERRED" : "PENDING_EXIT",
                breachDate + "收盘已触发，等待下一个可卖出市场日开盘，不以收盘止损位假设成交");
    }

    private Quote quote(Candidate candidate, LocalDate date, boolean atOpen, boolean buying,
                        Map<LocalDate, EastMoneyKLine> bars) {
        EastMoneyKLine bar = bars.get(date);
        if (bar == null || bar.volume() == null) return new Quote("MISSING_DATA", null);
        if (bar.volume().signum() <= 0) return new Quote("BLOCKED", null);
        BigDecimal value = atOpen ? bar.open() : bar.close();
        if (!positive(value)) return new Quote("MISSING_DATA", null);
        LocalDate previous = calendar.previousTradingDay(date);
        EastMoneyKLine previousBar = bars.get(previous);
        if (previousBar == null || !positive(previousBar.close())) return new Quote("MISSING_DATA", null);
        BigDecimal change = percent(value, previousBar.close());
        if (buying ? AsharePriceLimitRule.isLimitUpLike(candidate.symbol(), change)
                : AsharePriceLimitRule.isLimitDownLike(candidate.symbol(), change)) {
            return new Quote("BLOCKED", null);
        }
        return new Quote("AVAILABLE", value);
    }

    private BigDecimal adverse(LocalDate entry, LocalDate target, LocalDate asOf, BigDecimal baseline,
                               Map<LocalDate, EastMoneyKLine> bars) {
        LocalDate through = asOf.isBefore(target) ? asOf : target;
        BigDecimal low = baseline;
        LocalDate date = entry;
        while (date != null && !date.isAfter(through)) {
            EastMoneyKLine bar = bars.get(date);
            if (bar == null || !positive(bar.low()) || !positive(bar.volume())) return null;
            low = low.min(bar.low());
            date = calendar.verifiedTradingDayAfter(date, 1).orElse(null);
        }
        return date == null ? null : percent(low, baseline);
    }

    private Outcome unentered(String status, LocalDate entry, LocalDate target, String detail) {
        return new Outcome(status, status, entry, null, target, null, null, null, null,
                null, null, null, null, List.of("日线情景估算，不是实际成交。", detail));
    }

    private Exit exit(String status, String detail) { return new Exit(status, null, null, detail); }
    private BigDecimal price(BigDecimal value) { return value == null ? null : value.setScale(6, RoundingMode.HALF_UP); }
    private boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private BigDecimal percent(BigDecimal value, BigDecimal baseline) {
        return value.subtract(baseline).multiply(HUNDRED).divide(baseline, 6, RoundingMode.HALF_UP);
    }
    private BigDecimal net(BigDecimal value, BigDecimal baseline, Rules rules) {
        return value == null ? null : percent(value, baseline)
                .subtract(rules.roundTripCostBps().divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }
    private record Quote(String status, BigDecimal price) { }
    private record Exit(String status, LocalDate date, BigDecimal price, String detail) { }
}

package com.aistock.research.fiveday;

import com.aistock.research.firstboard.FirstBoardLeaderBaselineStock;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import static com.aistock.research.fiveday.FiveDayModels.*;

@Component
public class FiveDayEvaluator {
    private final TradingClockService clock;
    public FiveDayEvaluator(TradingClockService clock) { this.clock = clock; }

    public Optional<BigDecimal> pricePreselection(FirstBoardLeaderBaselineStock stock, List<EastMoneyKLine> bars,
                                                 LocalDate date, Rules rules) {
        List<EastMoneyKLine> window = exactWindow(stock.symbol(), bars, date, rules.rangeDays());
        if (window.isEmpty()) return Optional.empty();
        PriceShape shape = priceShape(window);
        return acceptableShape(shape, rules) ? Optional.of(shape.range()) : Optional.empty();
    }

    public Evaluation evaluate(FirstBoardLeaderBaselineStock stock, List<EastMoneyKLine> source,
                               List<EastMoneyFundFlowSnapshot> funds, LocalDate date,
                               Rules rules, List<String> sourceGaps) {
        List<EastMoneyKLine> bars = exactWindow(stock.symbol(), source, date,
                rules.recentTurnoverDays() + rules.priorTurnoverDays());
        if (bars.isEmpty()) return missing("所需25个市场交易日日K缺失或价格/成交量无效");
        if (bars.stream().anyMatch(b -> b.turnoverRate() == null || b.turnoverRate().signum() < 0)) {
            return missing("历史换手率未补齐，不能用成交量代替换手率");
        }
        List<EastMoneyKLine> recent = bars.subList(bars.size() - rules.recentTurnoverDays(), bars.size());
        if (recent.stream().anyMatch(b -> !positive(b.amount()))) return missing("近5日成交额缺失");
        PriceShape shape = priceShape(bars.subList(bars.size() - rules.rangeDays(), bars.size()));
        BigDecimal recentTurnover = average(recent, EastMoneyKLine::turnoverRate);
        BigDecimal priorTurnover = average(bars.subList(0, rules.priorTurnoverDays()), EastMoneyKLine::turnoverRate);
        if (!positive(priorTurnover)) return missing("前20日平均换手率无效");
        BigDecimal turnoverRatio = divide(recentTurnover, priorTurnover);
        BigDecimal averageAmount = average(recent, EastMoneyKLine::amount);
        if (!acceptableShape(shape, rules) || turnoverRatio.compareTo(rules.minTurnoverRatio()) < 0
                || averageAmount.compareTo(rules.minAverageAmount()) < 0) return new Evaluation(null, List.of());

        Map<LocalDate, EastMoneyFundFlowSnapshot> byDate = new LinkedHashMap<>();
        boolean conflicting = false;
        for (EastMoneyFundFlowSnapshot flow : funds == null ? List.<EastMoneyFundFlowSnapshot>of() : funds) {
            if (flow == null || !stock.symbol().equals(flow.symbol()) || flow.tradeDate() == null) continue;
            EastMoneyFundFlowSnapshot prior = byDate.putIfAbsent(flow.tradeDate(), flow);
            if (prior != null && !java.util.Objects.equals(prior.mainNetInflow(), flow.mainNetInflow())) conflicting = true;
        }
        List<String> gaps = new ArrayList<>(sourceGaps == null ? List.of() : sourceGaps);
        List<EastMoneyKLine> flowBars = bars.subList(bars.size() - rules.flowDays(), bars.size());
        boolean complete = !conflicting && flowBars.stream().allMatch(b -> byDate.containsKey(b.tradeDate())
                && byDate.get(b.tradeDate()).mainNetInflow() != null && positive(b.amount()));
        BigDecimal net = null;
        BigDecimal netRatio = null;
        int positiveDays = 0;
        String sourceUrl = null;
        if (complete) {
            net = BigDecimal.ZERO;
            BigDecimal amount = BigDecimal.ZERO;
            for (EastMoneyKLine bar : flowBars) {
                EastMoneyFundFlowSnapshot flow = byDate.get(bar.tradeDate());
                net = net.add(flow.mainNetInflow());
                amount = amount.add(bar.amount());
                if (flow.mainNetInflow().signum() > 0) positiveDays++;
                sourceUrl = flow.sourceUrl();
            }
            netRatio = divide(net, amount).multiply(new BigDecimal("100"));
        } else {
            gaps.add("最近5个交易日主力净流入数据不完整，不判定资金条件、不纳入资金配对统计");
        }
        boolean matches = complete && net.signum() > 0 && positiveDays >= rules.minPositiveFlowDays();
        BigDecimal close = bars.get(bars.size() - 1).close();
        BigDecimal stop = shape.low().multiply(BigDecimal.ONE.subtract(rules.stopBufferPercent().movePointLeft(2)))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal ceiling = close.multiply(BigDecimal.ONE.add(rules.maxEntryPremiumPercent().movePointLeft(2)))
                .min(divide(stop, BigDecimal.ONE.subtract(rules.maxInitialRiskPercent().movePointLeft(2))))
                .setScale(4, RoundingMode.HALF_UP);
        LocalDate entry = clock.verifiedTradingDayAfter(date, 1).orElse(null);
        LocalDate exit = entry == null ? null : clock.verifiedTradingDayAfter(entry, rules.holdingDays() - 1).orElse(null);
        if (exit == null) gaps.add("交易日历未覆盖计划持有期，不能估算退出日期");
        List<String> evidence = List.of(
                "近10日区间振幅 " + text(shape.range()) + "%，首尾收盘变化 " + text(shape.change()) + "%",
                "后5日收盘重心较前5日变化 " + text(shape.centerChange()) + "%",
                "近5日平均换手 " + text(recentTurnover) + "%，为此前20日的 " + text(turnoverRatio) + " 倍",
                "资金口径为东方财富主力净流入（大单分类），不代表所有主动买卖或真实机构身份",
                "参考价来自信号日收盘；次日开盘超过入场上限或已跌破失效位则放弃该次入场");
        return new Evaluation(new Candidate(stock.symbol(), stock.name(), stock.industry(), stock.board().name(),
                close, shape.high(), shape.low(), rounded(shape.range()), rounded(shape.change()),
                rounded(shape.centerChange()), rounded(recentTurnover), rounded(priorTurnover),
                rounded(turnoverRatio), rounded(averageAmount), net, rounded(netRatio), positiveDays,
                complete, matches, sourceUrl, stop, ceiling, entry, exit, evidence, gaps, bars), List.of());
    }

    List<EastMoneyKLine> exactWindow(String symbol, List<EastMoneyKLine> source, LocalDate date, int size) {
        if (date == null || clock.isMarketClosedDay(date)) return List.of();
        Map<LocalDate, EastMoneyKLine> byDate = new LinkedHashMap<>();
        for (EastMoneyKLine bar : source == null ? List.<EastMoneyKLine>of() : source) {
            if (bar == null || !symbol.equals(bar.symbol()) || bar.tradeDate() == null || bar.tradeDate().isAfter(date)) continue;
            if (byDate.putIfAbsent(bar.tradeDate(), bar) != null) return List.of();
        }
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate cursor = date;
        for (int i = 0; i < size; i++) {
            if (clock.verifiedTradingDayAfter(cursor, 0).isEmpty()) return List.of();
            EastMoneyKLine b = byDate.get(cursor);
            if (b == null || !positive(b.open()) || !positive(b.close()) || !positive(b.high())
                    || !positive(b.low()) || !positive(b.volume()) || b.high().compareTo(b.low()) < 0
                    || b.high().compareTo(b.open().max(b.close())) < 0
                    || b.low().compareTo(b.open().min(b.close())) > 0) return List.of();
            rows.add(b);
            cursor = clock.previousTradingDay(cursor);
        }
        rows.sort(Comparator.comparing(EastMoneyKLine::tradeDate));
        return rows;
    }
    private PriceShape priceShape(List<EastMoneyKLine> bars) {
        BigDecimal high = bars.stream().map(EastMoneyKLine::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = bars.stream().map(EastMoneyKLine::low).min(BigDecimal::compareTo).orElseThrow();
        int middle = bars.size() / 2;
        return new PriceShape(high, low, percent(high, low),
                percent(bars.get(bars.size() - 1).close(), bars.get(0).close()),
                percent(average(bars.subList(middle, bars.size()), EastMoneyKLine::close),
                        average(bars.subList(0, middle), EastMoneyKLine::close)));
    }
    private boolean acceptableShape(PriceShape s, Rules r) {
        return s.range().compareTo(r.maxRangePercent()) <= 0
                && s.change().abs().compareTo(r.maxAbsPriceChangePercent()) <= 0
                && s.centerChange().compareTo(r.minCenterChangePercent()) >= 0;
    }
    private BigDecimal average(List<EastMoneyKLine> bars, Function<EastMoneyKLine, BigDecimal> field) {
        return divide(bars.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add), BigDecimal.valueOf(bars.size()));
    }
    private BigDecimal percent(BigDecimal value, BigDecimal base) {
        return divide(value.subtract(base), base).multiply(new BigDecimal("100"));
    }
    private static BigDecimal divide(BigDecimal value, BigDecimal base) { return value.divide(base, 10, RoundingMode.HALF_UP); }
    private static BigDecimal rounded(BigDecimal value) { return value == null ? null : value.setScale(4, RoundingMode.HALF_UP); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private String text(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private Evaluation missing(String message) { return new Evaluation(null, List.of(message)); }
    private record PriceShape(BigDecimal high, BigDecimal low, BigDecimal range, BigDecimal change, BigDecimal centerChange) { }
    public record Evaluation(Candidate candidate, List<String> dataGaps) { }
}

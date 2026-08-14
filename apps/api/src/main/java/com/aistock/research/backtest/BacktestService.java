package com.aistock.research.backtest;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.shortterm.ShortTermRuleSet;
import com.aistock.research.shortterm.ShortTermTechnicalSignalEvaluation;
import com.aistock.research.shortterm.ShortTermTechnicalSignalEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class BacktestService {

    private static final Logger logger = LoggerFactory.getLogger(BacktestService.class);
    private static final int DEFAULT_LOOKBACK_DAYS = 900;
    private static final int DEFAULT_HOLDING_DAYS = 20;
    private static final int MAX_SYMBOLS = 20;
    private static final BigDecimal DEFAULT_MIN_VOLUME_RATIO = new BigDecimal("1.15");
    private static final BigDecimal DEFAULT_MAX_VOLUME_RATIO = new BigDecimal("3.20");
    private static final BigDecimal DEFAULT_MAX_DISTANCE_TO_MA20 = new BigDecimal("8.00");
    private static final BigDecimal DEFAULT_MIN_MA20_SLOPE = new BigDecimal("-0.20");
    private static final BigDecimal DEFAULT_STOP_LOSS = new BigDecimal("6.00");
    private static final BigDecimal DEFAULT_TAKE_PROFIT = new BigDecimal("18.00");
    private static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("0.03");
    private static final BigDecimal DEFAULT_STAMP_DUTY = new BigDecimal("0.05");
    private static final BigDecimal DEFAULT_SLIPPAGE = new BigDecimal("0.05");
    private static final BigDecimal DEFAULT_LIMIT_MOVE = new BigDecimal("9.80");
    private static final BigDecimal DEFAULT_MIN_RANGE60 = new BigDecimal("35");
    private static final BigDecimal DEFAULT_MAX_RANGE60 = new BigDecimal("92");
    private static final BigDecimal DEFAULT_FIRST_TARGET = new BigDecimal("2.50");
    private static final BigDecimal DEFAULT_SECOND_TARGET = new BigDecimal("4.50");
    private static final BigDecimal DEFAULT_HARD_STOP = new BigDecimal("3.50");
    private static final BigDecimal DEFAULT_TRAILING_DRAWDOWN = new BigDecimal("2.00");
    private static final int DEFAULT_OVERNIGHT_HOLDING_DAYS = 2;
    private static final BigDecimal FIRST_REDUCTION_RATIO = new BigDecimal("0.50");
    private static final int MIN_OVERNIGHT_HISTORY = 72;
    private static final List<String> OVERNIGHT_UNREPLAYED_GATES = List.of(
            "财报质量门禁",
            "市场情绪门禁",
            "尾盘分钟确认门禁",
            "实时行情新鲜度门禁"
    );

    private final EastMoneyClient eastMoneyClient;
    private final ShortTermTechnicalSignalEvaluator technicalSignalEvaluator;

    public BacktestService(EastMoneyClient eastMoneyClient) {
        this(eastMoneyClient, new ShortTermTechnicalSignalEvaluator());
    }

    @Autowired
    public BacktestService(
            EastMoneyClient eastMoneyClient,
            ShortTermTechnicalSignalEvaluator technicalSignalEvaluator
    ) {
        this.eastMoneyClient = eastMoneyClient;
        this.technicalSignalEvaluator = technicalSignalEvaluator;
    }

    public BacktestReport rightSideBacktest(
            String symbols,
            Integer lookbackDays,
            Integer holdingDays,
            BigDecimal minVolumeRatio,
            BigDecimal maxVolumeRatio,
            BigDecimal maxDistanceToMa20,
            BigDecimal stopLossPercent,
            BigDecimal takeProfitPercent,
            BigDecimal commissionPercent,
            BigDecimal stampDutyPercent,
            BigDecimal slippagePercent,
            BigDecimal limitMovePercent
    ) {
        List<String> parsedSymbols = parseSymbols(symbols);
        BacktestRuleSet ruleSet = new BacktestRuleSet(
                clampInt(lookbackDays, 240, 2500, DEFAULT_LOOKBACK_DAYS),
                clampInt(holdingDays, 3, 120, DEFAULT_HOLDING_DAYS),
                positiveOrDefault(minVolumeRatio, DEFAULT_MIN_VOLUME_RATIO),
                positiveOrDefault(maxVolumeRatio, DEFAULT_MAX_VOLUME_RATIO),
                positiveOrDefault(maxDistanceToMa20, DEFAULT_MAX_DISTANCE_TO_MA20),
                DEFAULT_MIN_MA20_SLOPE,
                positiveOrDefault(stopLossPercent, DEFAULT_STOP_LOSS),
                positiveOrDefault(takeProfitPercent, DEFAULT_TAKE_PROFIT),
                positiveOrDefault(commissionPercent, DEFAULT_COMMISSION),
                positiveOrDefault(stampDutyPercent, DEFAULT_STAMP_DUTY),
                positiveOrDefault(slippagePercent, DEFAULT_SLIPPAGE),
                positiveOrDefault(limitMovePercent, DEFAULT_LIMIT_MOVE),
                DEFAULT_MIN_RANGE60,
                DEFAULT_MAX_RANGE60
        );

        List<BacktestSymbolResult> results = parsedSymbols.stream()
                .map(symbol -> backtestSymbol(symbol, ruleSet))
                .toList();
        List<BacktestTrade> trades = results.stream()
                .flatMap(result -> result.trades().stream())
                .toList();

        return new BacktestReport(
                "短线右侧早期信号回测",
                List.of(
                        "信号只使用当日及以前 K 线计算，避免未来函数。",
                        "入场价使用信号日后一交易日开盘价，避免把收盘确认信号倒推成盘中买点。",
                        "持仓窗口内先检查止损，再检查止盈；日线无法知道盘中先后顺序，因此采用偏保守假设。",
                        "入场和出场都计入滑点，买卖双边计入佣金，卖出计入印花税；一字涨停无法买入、一字跌停无法卖出会跳过或延后。",
                        "回测结果只验证规则历史表现，不等同于未来收益承诺；样本过少时只给观察结论。"
                ),
                ruleSet,
                parsedSymbols,
                summarize(parsedSymbols.size(), trades),
                results,
                Instant.now()
        );
    }

    public OvernightBacktestReport overnightBacktest(
            String symbols,
            Integer lookbackDays,
            BigDecimal firstTargetPercent,
            BigDecimal secondTargetPercent,
            BigDecimal hardStopPercent,
            Integer maxHoldingTradingDays,
            BigDecimal commissionPercent,
            BigDecimal stampDutyPercent,
            BigDecimal slippagePercent,
            BigDecimal limitMovePercent,
            BigDecimal minVolumeRatio,
            BigDecimal maxDistanceToMa20Percent,
            BigDecimal trailingDrawdownPercent
    ) {
        List<String> parsedSymbols = parseSymbols(symbols);
        OvernightBacktestRuleSet ruleSet = new OvernightBacktestRuleSet(
                clampInt(lookbackDays, 240, 2500, DEFAULT_LOOKBACK_DAYS),
                positiveOrDefault(firstTargetPercent, DEFAULT_FIRST_TARGET),
                positiveOrDefault(secondTargetPercent, DEFAULT_SECOND_TARGET),
                positiveOrDefault(hardStopPercent, DEFAULT_HARD_STOP),
                clampInt(maxHoldingTradingDays, 1, 2, DEFAULT_OVERNIGHT_HOLDING_DAYS),
                positiveOrDefault(commissionPercent, DEFAULT_COMMISSION),
                positiveOrDefault(stampDutyPercent, DEFAULT_STAMP_DUTY),
                positiveOrDefault(slippagePercent, DEFAULT_SLIPPAGE),
                positiveOrDefault(limitMovePercent, DEFAULT_LIMIT_MOVE),
                positiveOrDefault(minVolumeRatio, DEFAULT_MIN_VOLUME_RATIO),
                positiveOrDefault(maxDistanceToMa20Percent, DEFAULT_MAX_DISTANCE_TO_MA20),
                nonNegativeOrDefault(trailingDrawdownPercent, DEFAULT_TRAILING_DRAWDOWN)
        );
        List<OvernightSymbolOutcome> outcomes = parsedSymbols.stream()
                .map(symbol -> overnightOutcome(symbol, ruleSet))
                .toList();
        List<OvernightBacktestTrade> trades = outcomes.stream()
                .flatMap(outcome -> outcome.trades().stream())
                .toList();
        List<OvernightBacktestSymbolResult> results = outcomes.stream()
                .map(OvernightSymbolOutcome::result)
                .toList();
        long sourceFailures = results.stream()
                .filter(result -> "SOURCE_FAILED".equals(result.status()))
                .count();
        long insufficientHistory = results.stream()
                .filter(result -> "INSUFFICIENT_HISTORY".equals(result.status()))
                .count();
        String status;
        String message;
        if (!results.isEmpty() && sourceFailures == results.size()) {
            status = "DATA_BLOCKED";
            message = "全部标的数据源失败，技术信号历史验证被阻断。";
        } else if (sourceFailures > 0 || insufficientHistory > 0) {
            status = "PARTIAL";
            message = "部分标的数据不完整：数据源失败 " + sourceFailures
                    + " 个，历史不足 " + insufficientHistory + " 个。";
        } else {
            status = "OK";
            message = trades.isEmpty()
                    ? "数据读取正常，但样本期内没有符合生产同源技术信号的交易。"
                    : "技术信号历史验证完成。";
        }
        return new OvernightBacktestReport(
                "短线 T+1/T+2 技术信号历史验证",
                List.of(
                        "技术信号逐交易日仅使用 rows[0..signalDay] 的日 K 线进行点时重放。",
                        "复用生产 ShortTermGoldenCrossAnalyzer，只接受最近已完成交易日内 CONFIRMED 金叉。",
                        "复用生产右侧技术 evaluator，只接受右侧早期确认。"
                ),
                OVERNIGHT_UNREPLAYED_GATES,
                List.of(
                        "信号仅使用信号日及以前 K 线，14:55 成交以信号日收盘价加买入滑点代理。",
                        "T+1 日线先处理不利跳空和硬止损，再处理止盈；只有收盘盈利、强于前收且站上 MA5 才延长至 T+2。",
                        "第一目标成交 50%，剩余仓位继续到第二目标、硬止损或时间退出；同日到达第二目标时按先第一目标、再第二目标处理。",
                        "首次止盈后的后续日线以前一已知峰值计算移动止损；日内移动止损与第二目标同时可达时，保守地先按移动止损退出。",
                        "正常退出不晚于 T+2；一字跌停无法成交时延后到首个可成交日，并单独标记 LIMIT_DOWN_DELAYED。",
                        "代理入场和每腿退出价已含双边滑点，净收益只再扣双边佣金和卖出印花税。"
                ),
                ruleSet,
                parsedSymbols,
                status,
                message,
                summarizeOvernight(parsedSymbols.size(), trades),
                results,
                trades,
                Instant.now()
        );
    }

    private OvernightSymbolOutcome overnightOutcome(String symbol, OvernightBacktestRuleSet ruleSet) {
        List<EastMoneyKLine> rows;
        try {
            LocalDate end = LocalDate.now();
            rows = eastMoneyClient.fetchDailyKLines(symbol, end.minusDays(ruleSet.lookbackDays() + 180L), end).stream()
                    .filter(row -> row.tradeDate() != null && row.close() != null)
                    .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                    .toList();
        } catch (RuntimeException exception) {
            logger.warn("隔夜回测 K 线获取失败：{}", symbol, exception);
            String gap = "数据源失败：" + rootMessage(exception);
            return new OvernightSymbolOutcome(
                    new OvernightBacktestSymbolResult(symbol, "SOURCE_FAILED", 0, 0, List.of(gap)),
                    List.of()
            );
        }
        if (rows.size() < MIN_OVERNIGHT_HISTORY) {
            String gap = "历史 K 线不足：需要至少 " + MIN_OVERNIGHT_HISTORY + " 条，实际 " + rows.size() + " 条";
            return new OvernightSymbolOutcome(
                    new OvernightBacktestSymbolResult(
                            symbol,
                            "INSUFFICIENT_HISTORY",
                            rows.size(),
                            0,
                            List.of(gap)
                    ),
                    List.of()
            );
        }

        List<OvernightBacktestTrade> trades = overnightTrades(symbol, rows, ruleSet);
        String status = trades.isEmpty() ? "NO_SIGNAL" : "OK";
        List<String> dataGaps = trades.isEmpty()
                ? List.of("样本期内没有同时满足 CONFIRMED 金叉与右侧早期确认的技术信号")
                : List.of();
        return new OvernightSymbolOutcome(
                new OvernightBacktestSymbolResult(symbol, status, rows.size(), trades.size(), dataGaps),
                trades
        );
    }

    private List<OvernightBacktestTrade> overnightTrades(
            String symbol,
            List<EastMoneyKLine> rows,
            OvernightBacktestRuleSet ruleSet
    ) {
        List<OvernightBacktestTrade> trades = new ArrayList<>();
        int index = 70;
        while (index < rows.size() - 1) {
            EastMoneyKLine signalRow = rows.get(index);
            EastMoneyKLine previous = rows.get(index - 1);
            ShortTermTechnicalSignalEvaluation signal = technicalSignalEvaluator.evaluate(
                    rows.subList(0, index + 1),
                    signalRow.close(),
                    false,
                    overnightTechnicalRules(ruleSet)
            );
            if (!signal.eligibleForOvernightValidation()
                    || isLimitUpLock(signalRow, previous, overnightPriceLimitRules(ruleSet))) {
                index++;
                continue;
            }
            OvernightExitPlan exit = resolveOvernightExit(rows, index, ruleSet);
            if (exit == null) {
                index++;
                continue;
            }
            trades.add(toOvernightTrade(symbol, rows, index, exit, ruleSet));
            index = Math.max(exit.finalExitIndex() + 1, index + 1);
        }
        return trades;
    }

    private ShortTermRuleSet overnightTechnicalRules(OvernightBacktestRuleSet ruleSet) {
        return new ShortTermRuleSet(
                1,
                ruleSet.lookbackDays(),
                BigDecimal.ZERO,
                ruleSet.minVolumeRatio(),
                new BigDecimal("4.00"),
                ruleSet.maxDistanceToMa20Percent(),
                BigDecimal.ZERO
        );
    }

    private BacktestRuleSet overnightPriceLimitRules(OvernightBacktestRuleSet ruleSet) {
        return new BacktestRuleSet(
                ruleSet.lookbackDays(),
                DEFAULT_HOLDING_DAYS,
                DEFAULT_MIN_VOLUME_RATIO,
                DEFAULT_MAX_VOLUME_RATIO,
                DEFAULT_MAX_DISTANCE_TO_MA20,
                DEFAULT_MIN_MA20_SLOPE,
                ruleSet.hardStopPercent(),
                ruleSet.secondTargetPercent(),
                ruleSet.commissionPercent(),
                ruleSet.stampDutyPercent(),
                ruleSet.slippagePercent(),
                ruleSet.limitMovePercent(),
                DEFAULT_MIN_RANGE60,
                DEFAULT_MAX_RANGE60
        );
    }

    private OvernightExitPlan resolveOvernightExit(
            List<EastMoneyKLine> rows,
            int signalIndex,
            OvernightBacktestRuleSet ruleSet
    ) {
        EastMoneyKLine signal = rows.get(signalIndex);
        BigDecimal proxyEntry = signal.close().multiply(BigDecimal.ONE.add(rate(ruleSet.slippagePercent())));
        BigDecimal stopPrice = proxyEntry.multiply(BigDecimal.ONE.subtract(rate(ruleSet.hardStopPercent())));
        BigDecimal firstTargetPrice = proxyEntry.multiply(BigDecimal.ONE.add(rate(ruleSet.firstTargetPercent())));
        BigDecimal secondTargetPrice = proxyEntry.multiply(BigDecimal.ONE.add(rate(ruleSet.secondTargetPercent())));
        List<OvernightBaseExitLeg> legs = new ArrayList<>();
        int t1Index = signalIndex + 1;
        OvernightDayState state = resolveOvernightDay(
                rows, t1Index, stopPrice, firstTargetPrice, secondTargetPrice,
                BigDecimal.ONE, null, legs, ruleSet
        );
        if (state.delayed()) {
            return delayedOvernightExit(rows, t1Index, state.remaining(), legs, ruleSet);
        }
        if (state.remaining().compareTo(BigDecimal.ZERO) == 0) {
            return exitPlan(legs);
        }

        EastMoneyKLine t1 = rows.get(t1Index);
        BigDecimal t1Ma5 = movingAverage(rows.subList(0, t1Index + 1), 5);
        boolean extendToT2 = ruleSet.maxHoldingTradingDays() >= 2
                && t1Index + 1 < rows.size()
                && t1.close() != null
                && t1.close().compareTo(proxyEntry) > 0
                && t1.close().compareTo(signal.close()) > 0
                && t1Ma5 != null
                && t1.close().compareTo(t1Ma5) > 0;
        if (!extendToT2) {
            if (isLimitDownLock(t1, signal, overnightPriceLimitRules(ruleSet))) {
                return delayedOvernightExit(rows, t1Index, state.remaining(), legs, ruleSet);
            }
            legs.add(new OvernightBaseExitLeg(t1Index, state.remaining(), t1.close(), "T1_TIME_EXIT"));
            return exitPlan(legs);
        }

        int t2Index = t1Index + 1;
        state = resolveOvernightDay(
                rows, t2Index, stopPrice, firstTargetPrice, secondTargetPrice,
                state.remaining(), state.trailingPeak(), legs, ruleSet
        );
        if (state.delayed()) {
            return delayedOvernightExit(rows, t2Index, state.remaining(), legs, ruleSet);
        }
        if (state.remaining().compareTo(BigDecimal.ZERO) == 0) {
            return exitPlan(legs);
        }
        EastMoneyKLine t2 = rows.get(t2Index);
        if (isLimitDownLock(t2, t1, overnightPriceLimitRules(ruleSet))) {
            return delayedOvernightExit(rows, t2Index, state.remaining(), legs, ruleSet);
        }
        legs.add(new OvernightBaseExitLeg(t2Index, state.remaining(), t2.close(), "T2_TIME_EXIT"));
        return exitPlan(legs);
    }

    private OvernightDayState resolveOvernightDay(
            List<EastMoneyKLine> rows,
            int index,
            BigDecimal stopPrice,
            BigDecimal firstTargetPrice,
            BigDecimal secondTargetPrice,
            BigDecimal remaining,
            BigDecimal trailingPeak,
            List<OvernightBaseExitLeg> legs,
            OvernightBacktestRuleSet ruleSet
    ) {
        EastMoneyKLine row = rows.get(index);
        EastMoneyKLine previous = rows.get(index - 1);
        BacktestRuleSet priceLimitRules = overnightPriceLimitRules(ruleSet);
        BigDecimal trailingStopPrice = trailingPeak == null
                ? null
                : trailingPeak.multiply(BigDecimal.ONE.subtract(rate(ruleSet.trailingDrawdownPercent())));
        if (isLimitDownLock(row, previous, priceLimitRules)) {
            if ((row.open() != null && row.open().compareTo(stopPrice) <= 0)
                    || (row.low() != null && row.low().compareTo(stopPrice) <= 0)
                    || (trailingStopPrice != null && row.open() != null
                    && row.open().compareTo(trailingStopPrice) <= 0)
                    || (trailingStopPrice != null && row.low() != null
                    && row.low().compareTo(trailingStopPrice) <= 0)) {
                return new OvernightDayState(remaining, trailingPeak, true);
            }
            return new OvernightDayState(remaining, trailingPeak, false);
        }
        if (row.open() != null && row.open().compareTo(stopPrice) <= 0) {
            legs.add(new OvernightBaseExitLeg(index, remaining, row.open(), "HARD_STOP"));
            return new OvernightDayState(BigDecimal.ZERO, trailingPeak, false);
        }
        if (row.low() != null && row.low().compareTo(stopPrice) <= 0) {
            legs.add(new OvernightBaseExitLeg(index, remaining, stopPrice, "HARD_STOP"));
            return new OvernightDayState(BigDecimal.ZERO, trailingPeak, false);
        }
        if (trailingStopPrice != null
                && row.open() != null
                && row.open().compareTo(trailingStopPrice) <= 0) {
            legs.add(new OvernightBaseExitLeg(index, remaining, row.open(), "TRAILING_STOP"));
            return new OvernightDayState(BigDecimal.ZERO, trailingPeak, false);
        }
        if (trailingStopPrice != null
                && row.low() != null
                && row.low().compareTo(trailingStopPrice) <= 0) {
            legs.add(new OvernightBaseExitLeg(index, remaining, trailingStopPrice, "TRAILING_STOP"));
            return new OvernightDayState(BigDecimal.ZERO, trailingPeak, false);
        }
        boolean firstAlreadyHit = legs.stream()
                .anyMatch(leg -> "FIRST_TARGET".equals(leg.reason()));
        if (!firstAlreadyHit
                && remaining.compareTo(FIRST_REDUCTION_RATIO) > 0
                && row.high() != null
                && row.high().compareTo(firstTargetPrice) >= 0) {
            legs.add(new OvernightBaseExitLeg(
                    index,
                    FIRST_REDUCTION_RATIO,
                    firstTargetPrice,
                    "FIRST_TARGET"
            ));
            remaining = remaining.subtract(FIRST_REDUCTION_RATIO);
            trailingPeak = max(firstTargetPrice, row.high());
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0
                && row.high() != null
                && row.high().compareTo(secondTargetPrice) >= 0) {
            legs.add(new OvernightBaseExitLeg(index, remaining, secondTargetPrice, "SECOND_TARGET"));
            return new OvernightDayState(BigDecimal.ZERO, trailingPeak, false);
        }
        if (firstAlreadyHit && row.high() != null) {
            trailingPeak = max(trailingPeak, row.high());
        }
        return new OvernightDayState(remaining, trailingPeak, false);
    }

    private OvernightExitPlan delayedOvernightExit(
            List<EastMoneyKLine> rows,
            int lockedIndex,
            BigDecimal remaining,
            List<OvernightBaseExitLeg> existingLegs,
            OvernightBacktestRuleSet ruleSet
    ) {
        BacktestRuleSet priceLimitRules = overnightPriceLimitRules(ruleSet);
        for (int index = lockedIndex + 1; index < rows.size(); index++) {
            EastMoneyKLine row = rows.get(index);
            EastMoneyKLine previous = rows.get(index - 1);
            if (!isLimitDownLock(row, previous, priceLimitRules)) {
                BigDecimal price = row.open() != null && row.open().compareTo(BigDecimal.ZERO) > 0
                        ? row.open()
                        : row.close();
                if (price == null) {
                    return null;
                }
                List<OvernightBaseExitLeg> legs = new ArrayList<>(existingLegs);
                legs.add(new OvernightBaseExitLeg(index, remaining, price, "LIMIT_DOWN_DELAYED"));
                return exitPlan(legs);
            }
        }
        return null;
    }

    private OvernightExitPlan exitPlan(List<OvernightBaseExitLeg> legs) {
        if (legs.isEmpty()) {
            return null;
        }
        OvernightBaseExitLeg finalLeg = legs.get(legs.size() - 1);
        boolean firstTargetHit = legs.stream().anyMatch(leg -> "FIRST_TARGET".equals(leg.reason()));
        boolean secondTargetHit = legs.stream().anyMatch(leg -> "SECOND_TARGET".equals(leg.reason()));
        return new OvernightExitPlan(
                finalLeg.exitIndex(),
                finalLeg.reason(),
                firstTargetHit,
                secondTargetHit,
                List.copyOf(legs)
        );
    }

    private OvernightBacktestTrade toOvernightTrade(
            String symbol,
            List<EastMoneyKLine> rows,
            int signalIndex,
            OvernightExitPlan exit,
            OvernightBacktestRuleSet ruleSet
    ) {
        EastMoneyKLine signal = rows.get(signalIndex);
        BigDecimal proxyEntry = signal.close().multiply(BigDecimal.ONE.add(rate(ruleSet.slippagePercent())));
        List<OvernightBacktestExitLeg> exitLegs = exit.legs().stream()
                .map(leg -> new OvernightBacktestExitLeg(
                        rows.get(leg.exitIndex()).tradeDate(),
                        leg.positionRatio().setScale(2, RoundingMode.HALF_UP),
                        executionMoney(leg.baseExitPrice().multiply(BigDecimal.ONE.subtract(rate(ruleSet.slippagePercent())))),
                        leg.reason()
                ))
                .toList();
        BigDecimal weightedExitPrice = exitLegs.stream()
                .map(leg -> leg.executablePrice().multiply(leg.positionRatio()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<EastMoneyKLine> holdingRows = rows.subList(signalIndex + 1, exit.finalExitIndex() + 1);
        BigDecimal maxHigh = holdingRows.stream()
                .map(EastMoneyKLine::high)
                .filter(value -> value != null)
                .max(BigDecimal::compareTo)
                .orElse(weightedExitPrice);
        BigDecimal minLow = holdingRows.stream()
                .map(EastMoneyKLine::low)
                .filter(value -> value != null)
                .min(BigDecimal::compareTo)
                .orElse(weightedExitPrice);
        BigDecimal commissionCost = ruleSet.commissionPercent().multiply(new BigDecimal("2"));
        BigDecimal slippageCost = ruleSet.slippagePercent().multiply(new BigDecimal("2"));
        BigDecimal totalCost = commissionCost.add(ruleSet.stampDutyPercent()).add(slippageCost);
        BigDecimal executableReturn = percent(weightedExitPrice.subtract(proxyEntry), proxyEntry);
        BigDecimal netReturn = executableReturn == null
                ? null
                : executableReturn.subtract(commissionCost).subtract(ruleSet.stampDutyPercent());
        EastMoneyKLine t1 = rows.get(signalIndex + 1);
        LocalDate t2Date = signalIndex + 2 < rows.size() ? rows.get(signalIndex + 2).tradeDate() : null;
        BigDecimal gap = t1.open() == null ? null : percent(t1.open().subtract(proxyEntry), proxyEntry);
        return new OvernightBacktestTrade(
                symbol,
                signal.tradeDate(),
                executionMoney(proxyEntry),
                t1.tradeDate(),
                t2Date,
                rows.get(exit.finalExitIndex()).tradeDate(),
                executionMoney(weightedExitPrice),
                exit.firstTargetHit(),
                exit.secondTargetHit(),
                exitLegs,
                executionMoney(weightedExitPrice),
                scale(netReturn),
                scale(percent(maxHigh.subtract(proxyEntry), proxyEntry)),
                scale(percent(minLow.subtract(proxyEntry), proxyEntry)),
                scale(gap),
                exit.finalExitIndex() - signalIndex,
                scale(commissionCost),
                scale(ruleSet.stampDutyPercent()),
                scale(slippageCost),
                scale(totalCost),
                exit.exitReason()
        );
    }

    private OvernightBacktestSummary summarizeOvernight(
            int symbolCount,
            List<OvernightBacktestTrade> trades
    ) {
        if (trades.isEmpty()) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            return new OvernightBacktestSummary(
                    symbolCount, 0, zero, zero, zero, zero, zero,
                    zero, zero, zero, zero, zero, null, null, "样本不足"
            );
        }
        int positive = countOvernight(trades, trade -> trade.netReturnPercent().compareTo(BigDecimal.ZERO) > 0);
        int firstTarget = countOvernight(trades, OvernightBacktestTrade::firstTargetHit);
        int secondTarget = countOvernight(trades, OvernightBacktestTrade::secondTargetHit);
        int hardStop = countOvernight(trades, trade -> "HARD_STOP".equals(trade.exitReason()));
        int timeStop = countOvernight(trades, trade ->
                "T1_TIME_EXIT".equals(trade.exitReason()) || "T2_TIME_EXIT".equals(trade.exitReason()));
        int gapDown = countOvernight(trades, trade ->
                trade.gapPercent() != null && trade.gapPercent().compareTo(BigDecimal.ZERO) < 0);
        BigDecimal averageReturn = average(trades.stream().map(OvernightBacktestTrade::netReturnPercent).toList());
        BigDecimal averageDrawdown = average(trades.stream().map(OvernightBacktestTrade::maxDrawdownPercent).toList());
        return new OvernightBacktestSummary(
                symbolCount,
                trades.size(),
                ratePercent(positive, trades.size()),
                scale(averageReturn),
                median(trades.stream().map(OvernightBacktestTrade::netReturnPercent).toList()),
                scale(average(trades.stream().map(OvernightBacktestTrade::maxRunupPercent).toList())),
                scale(averageDrawdown),
                ratePercent(firstTarget, trades.size()),
                ratePercent(secondTarget, trades.size()),
                ratePercent(hardStop, trades.size()),
                ratePercent(timeStop, trades.size()),
                ratePercent(gapDown, trades.size()),
                trades.stream().map(OvernightBacktestTrade::signalDate).min(LocalDate::compareTo).orElse(null),
                trades.stream().map(OvernightBacktestTrade::signalDate).max(LocalDate::compareTo).orElse(null),
                overnightConclusion(trades.size(), positive, averageReturn, averageDrawdown)
        );
    }

    private int countOvernight(
            List<OvernightBacktestTrade> trades,
            java.util.function.Predicate<OvernightBacktestTrade> predicate
    ) {
        return (int) trades.stream().filter(predicate).count();
    }

    private BigDecimal ratePercent(int count, int total) {
        return scale(BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")));
    }

    private BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream()
                .filter(value -> value != null)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return null;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return scale(sorted.get(middle));
        }
        return scale(sorted.get(middle - 1).add(sorted.get(middle))
                .divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP));
    }

    private String overnightConclusion(
            int sampleCount,
            int positiveCount,
            BigDecimal averageReturn,
            BigDecimal averageDrawdown
    ) {
        if (sampleCount < 5) {
            return "样本偏少，只能观察";
        }
        BigDecimal positiveRate = ratePercent(positiveCount, sampleCount);
        if (positiveRate.compareTo(new BigDecimal("55")) >= 0
                && averageReturn.compareTo(BigDecimal.ZERO) > 0
                && averageDrawdown.compareTo(new BigDecimal("-5")) >= 0) {
            return "隔夜历史样本支持";
        }
        if (averageReturn.compareTo(BigDecimal.ZERO) > 0) {
            return "隔夜正收益但波动需复核";
        }
        return "隔夜历史样本偏弱";
    }

    public BacktestReport rightSideBacktest(
            String symbols,
            Integer lookbackDays,
            Integer holdingDays,
            BigDecimal minVolumeRatio,
            BigDecimal maxVolumeRatio,
            BigDecimal maxDistanceToMa20,
            BigDecimal stopLossPercent,
            BigDecimal takeProfitPercent
    ) {
        return rightSideBacktest(
                symbols,
                lookbackDays,
                holdingDays,
                minVolumeRatio,
                maxVolumeRatio,
                maxDistanceToMa20,
                stopLossPercent,
                takeProfitPercent,
                null,
                null,
                null,
                null
        );
    }

    private BacktestSymbolResult backtestSymbol(String symbol, BacktestRuleSet ruleSet) {
        List<String> dataGaps = new ArrayList<>();
        List<EastMoneyKLine> rows;
        try {
            LocalDate end = LocalDate.now();
            rows = eastMoneyClient.fetchDailyKLines(symbol, end.minusDays(ruleSet.lookbackDays() + 180L), end).stream()
                    .filter(row -> row.tradeDate() != null && row.close() != null)
                    .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                    .toList();
        } catch (RuntimeException exception) {
            logger.warn("右侧回测 K 线获取失败：{}", symbol, exception);
            rows = List.of();
            dataGaps.add("K 线获取失败：" + rootMessage(exception));
        }

        if (rows.size() < 90) {
            dataGaps.add("K 线不足 90 条，无法稳定计算 60 日位置和 20 日线斜率");
        }

        List<BacktestTrade> trades = simulate(symbol, rows, ruleSet);
        if (trades.isEmpty() && dataGaps.isEmpty()) {
            dataGaps.add("样本期内没有触发右侧早期信号");
        }
        return new BacktestSymbolResult(
                symbol,
                rows.size(),
                trades.size(),
                summarize(1, trades),
                trades,
                dataGaps
        );
    }

    private List<BacktestTrade> simulate(String symbol, List<EastMoneyKLine> rows, BacktestRuleSet ruleSet) {
        List<BacktestTrade> trades = new ArrayList<>();
        if (rows.size() < 90) {
            return trades;
        }

        int index = 70;
        while (index < rows.size() - 2) {
            SignalSnapshot signal = signalAt(rows, index, ruleSet);
            if (!signal.confirmed()) {
                index++;
                continue;
            }
            int entryIndex = index + 1;
            EastMoneyKLine signalRow = rows.get(index);
            EastMoneyKLine entry = rows.get(entryIndex);
            if (isLimitUpLock(entry, signalRow, ruleSet)) {
                index++;
                continue;
            }
            BigDecimal entryPrice = executableEntryPrice(entry, ruleSet);
            TradeExit exit = resolveExit(rows, entryIndex, entryPrice, ruleSet);
            EastMoneyKLine exitRow = rows.get(exit.exitIndex());
            BigDecimal exitPrice = exit.exitPrice();
            BigDecimal grossReturn = percent(exitPrice.subtract(entryPrice), entryPrice);
            BigDecimal totalCost = totalCostPercent(ruleSet);
            BigDecimal netReturn = grossReturn == null ? null : grossReturn.subtract(totalCost);
            trades.add(new BacktestTrade(
                    symbol,
                    signalRow.tradeDate(),
                    entry.tradeDate(),
                    exitRow.tradeDate(),
                    money(entryPrice),
                    money(exitPrice),
                    scale(grossReturn),
                    scale(netReturn),
                    scale(exit.maxDrawdownPercent()),
                    scale(totalCost),
                    exit.exitIndex() - entryIndex + 1,
                    exit.exitReason(),
                    withCostEvidence(signal.evidence(), ruleSet, totalCost)
            ));
            index = Math.max(exit.exitIndex() + 1, index + 1);
        }
        return trades;
    }

    private SignalSnapshot signalAt(List<EastMoneyKLine> rows, int index, BacktestRuleSet ruleSet) {
        List<EastMoneyKLine> history = rows.subList(0, index + 1);
        List<EastMoneyKLine> previousRows = rows.subList(0, index);
        EastMoneyKLine last = rows.get(index);
        EastMoneyKLine previous = rows.get(index - 1);
        BigDecimal close = last.close();
        BigDecimal ma5 = movingAverage(history, 5);
        BigDecimal ma10 = movingAverage(history, 10);
        BigDecimal ma20 = movingAverage(history, 20);
        BigDecimal ma20Slope = movingAverageSlope(history, 20, 5);
        BigDecimal previousHigh20 = high(previousRows, 20);
        BigDecimal low60 = low(history, 60);
        BigDecimal high60 = high(history, 60);
        BigDecimal volumeRatio20 = volumeRatio(history, 20);
        BigDecimal distanceToMa20 = percent(close.subtract(nullToZero(ma20)), ma20);
        BigDecimal breakout20 = percent(close.subtract(nullToZero(previousHigh20)), previousHigh20);
        BigDecimal range60 = rangePosition(close, low60, high60);

        boolean aboveMa20 = ma20 != null && close.compareTo(ma20) > 0;
        boolean ma5AboveMa10 = ma5 != null && ma10 != null && ma5.compareTo(ma10) >= 0;
        boolean ma20Turning = ma20Slope != null && ma20Slope.compareTo(ruleSet.minMa20SlopePercent()) >= 0;
        boolean nearMa20 = distanceToMa20 != null
                && distanceToMa20.compareTo(BigDecimal.ZERO) >= 0
                && distanceToMa20.compareTo(ruleSet.maxDistanceToMa20Percent()) <= 0;
        boolean middleRange = range60 != null
                && range60.compareTo(ruleSet.minRange60Percent()) >= 0
                && range60.compareTo(ruleSet.maxRange60Percent()) <= 0;
        boolean volumeConfirmed = volumeRatio20 != null
                && volumeRatio20.compareTo(ruleSet.minVolumeRatio()) >= 0
                && volumeRatio20.compareTo(ruleSet.maxVolumeRatio()) <= 0;
        boolean crossedMa20 = previous.close() != null && ma20 != null && previous.close().compareTo(ma20) <= 0 && aboveMa20;
        boolean brokePreviousHigh20 = previousHigh20 != null && close.compareTo(previousHigh20) >= 0;

        boolean confirmed = aboveMa20
                && ma5AboveMa10
                && ma20Turning
                && nearMa20
                && middleRange
                && volumeConfirmed
                && (crossedMa20 || brokePreviousHigh20);

        return new SignalSnapshot(
                confirmed,
                List.of(
                        "MA20斜率 " + valueText(ma20Slope) + "%",
                        "距MA20 " + valueText(distanceToMa20) + "%",
                        "20日量比 " + valueText(volumeRatio20),
                        "突破前20日高点 " + valueText(breakout20) + "%",
                        "60日区间位置 " + valueText(range60) + "%"
                )
        );
    }

    private TradeExit resolveExit(List<EastMoneyKLine> rows, int entryIndex, BigDecimal entryPrice, BacktestRuleSet ruleSet) {
        BigDecimal stopPrice = entryPrice.multiply(BigDecimal.ONE.subtract(ruleSet.stopLossPercent().divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)));
        BigDecimal takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.add(ruleSet.takeProfitPercent().divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)));
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        int maxExitIndex = Math.min(rows.size() - 1, entryIndex + ruleSet.holdingDays() - 1);
        for (int index = entryIndex; index <= maxExitIndex; index++) {
            EastMoneyKLine row = rows.get(index);
            EastMoneyKLine previous = index > 0 ? rows.get(index - 1) : null;
            if (row.low() != null) {
                BigDecimal drawdown = percent(row.low().subtract(entryPrice), entryPrice);
                if (drawdown != null && drawdown.compareTo(maxDrawdown) < 0) {
                    maxDrawdown = drawdown;
                }
                if (row.low().compareTo(stopPrice) <= 0) {
                    if (isLimitDownLock(row, previous, ruleSet)) {
                        continue;
                    }
                    return new TradeExit(index, money(executableSellPrice(stopPrice, ruleSet)), scale(maxDrawdown), "STOP_LOSS");
                }
            }
            if (row.high() != null && row.high().compareTo(takeProfitPrice) >= 0) {
                if (isLimitDownLock(row, previous, ruleSet)) {
                    continue;
                }
                return new TradeExit(index, money(executableSellPrice(takeProfitPrice, ruleSet)), scale(maxDrawdown), "TAKE_PROFIT");
            }
        }
        int timeExitIndex = resolveTimeExitIndex(rows, maxExitIndex, ruleSet);
        EastMoneyKLine exit = rows.get(timeExitIndex);
        return new TradeExit(timeExitIndex, money(executableExitPrice(exit, ruleSet)), scale(maxDrawdown), timeExitIndex == maxExitIndex ? "TIME_EXIT" : "TIME_EXIT_DELAYED");
    }

    private BigDecimal executableEntryPrice(EastMoneyKLine row, BacktestRuleSet ruleSet) {
        BigDecimal base = row.open() != null && row.open().compareTo(BigDecimal.ZERO) > 0 ? row.open() : row.close();
        return base.multiply(BigDecimal.ONE.add(rate(ruleSet.slippagePercent())));
    }

    private BigDecimal executableExitPrice(EastMoneyKLine row, BacktestRuleSet ruleSet) {
        return executableSellPrice(row.close(), ruleSet);
    }

    private BigDecimal executableSellPrice(BigDecimal basePrice, BacktestRuleSet ruleSet) {
        return basePrice.multiply(BigDecimal.ONE.subtract(rate(ruleSet.slippagePercent())));
    }

    private BigDecimal totalCostPercent(BacktestRuleSet ruleSet) {
        return ruleSet.commissionPercent().multiply(new BigDecimal("2"))
                .add(ruleSet.stampDutyPercent());
    }

    private List<String> withCostEvidence(List<String> signalEvidence, BacktestRuleSet ruleSet, BigDecimal totalCost) {
        List<String> evidence = new ArrayList<>(signalEvidence);
        evidence.add("成本假设 买入/卖出成交价已按单边滑点 " + valueText(ruleSet.slippagePercent())
                + "% 修正；买卖佣金合计 " + valueText(ruleSet.commissionPercent().multiply(new BigDecimal("2")))
                + "%，印花税 " + valueText(ruleSet.stampDutyPercent())
                + "%，费用合计约 " + valueText(totalCost) + "%");
        return evidence;
    }

    private boolean isLimitUpLock(EastMoneyKLine row, EastMoneyKLine previous, BacktestRuleSet ruleSet) {
        if (row == null || previous == null || row.high() == null || row.low() == null || row.close() == null || previous.close() == null) {
            return false;
        }
        BigDecimal change = percent(row.close().subtract(previous.close()), previous.close());
        return row.high().compareTo(row.low()) == 0
                && change != null
                && change.compareTo(ruleSet.limitMovePercent()) >= 0;
    }

    private boolean isLimitDownLock(EastMoneyKLine row, EastMoneyKLine previous, BacktestRuleSet ruleSet) {
        if (row == null || previous == null || row.high() == null || row.low() == null || row.close() == null || previous.close() == null) {
            return false;
        }
        BigDecimal change = percent(row.close().subtract(previous.close()), previous.close());
        return row.high().compareTo(row.low()) == 0
                && change != null
                && change.compareTo(ruleSet.limitMovePercent().negate()) <= 0;
    }

    private int resolveTimeExitIndex(List<EastMoneyKLine> rows, int preferredExitIndex, BacktestRuleSet ruleSet) {
        int index = preferredExitIndex;
        while (index < rows.size()) {
            EastMoneyKLine previous = index > 0 ? rows.get(index - 1) : null;
            if (!isLimitDownLock(rows.get(index), previous, ruleSet)) {
                return index;
            }
            index++;
        }
        return preferredExitIndex;
    }

    private BacktestSummary summarize(int symbolCount, List<BacktestTrade> trades) {
        if (trades.isEmpty()) {
            return new BacktestSummary(
                    symbolCount,
                    0,
                    0,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    null,
                    null,
                    null,
                    "样本不足"
            );
        }
        int wins = (int) trades.stream()
                .filter(trade -> trade.returnPercent().compareTo(BigDecimal.ZERO) > 0)
                .count();
        BigDecimal averageReturn = average(trades.stream().map(BacktestTrade::returnPercent).toList());
        BigDecimal averageDrawdown = average(trades.stream().map(BacktestTrade::maxDrawdownPercent).toList());
        BigDecimal positiveSum = trades.stream()
                .map(BacktestTrade::returnPercent)
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal negativeSum = trades.stream()
                .map(BacktestTrade::returnPercent)
                .filter(value -> value.compareTo(BigDecimal.ZERO) < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();
        BigDecimal profitFactor = negativeSum.compareTo(BigDecimal.ZERO) == 0
                ? null
                : positiveSum.divide(negativeSum, 4, RoundingMode.HALF_UP);
        return new BacktestSummary(
                symbolCount,
                trades.size(),
                wins,
                scale(BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades.size()), 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))),
                scale(averageReturn),
                scale(averageDrawdown),
                trades.stream().map(BacktestTrade::returnPercent).max(BigDecimal::compareTo).map(this::scale).orElse(null),
                trades.stream().map(BacktestTrade::returnPercent).min(BigDecimal::compareTo).map(this::scale).orElse(null),
                profitFactor == null ? null : scale(profitFactor),
                conclusion(trades.size(), wins, averageReturn, averageDrawdown)
        );
    }

    private String conclusion(int tradeCount, int wins, BigDecimal averageReturn, BigDecimal averageDrawdown) {
        if (tradeCount < 5) {
            return "样本偏少，只能观察";
        }
        BigDecimal winRate = BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(tradeCount), 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        if (winRate.compareTo(new BigDecimal("55")) >= 0
                && averageReturn.compareTo(new BigDecimal("2")) >= 0
                && averageDrawdown.compareTo(new BigDecimal("-8")) >= 0) {
            return "历史样本支持";
        }
        if (averageReturn.compareTo(BigDecimal.ZERO) > 0) {
            return "正收益但波动需复核";
        }
        return "历史样本偏弱";
    }

    private List<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (String item : symbols.split("[,，\\s]+")) {
            String symbol = item == null ? "" : item.trim();
            if (symbol.matches("\\d{6}") && !parsed.contains(symbol)) {
                parsed.add(symbol);
            }
            if (parsed.size() >= MAX_SYMBOLS) {
                break;
            }
        }
        return parsed;
    }

    private BigDecimal movingAverage(List<EastMoneyKLine> rows, int window) {
        List<EastMoneyKLine> slice = lastRows(rows, window);
        if (slice.size() < window) {
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

    private BigDecimal rate(BigDecimal percent) {
        return percent.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    }

    private int clampInt(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
    }

    private BigDecimal nonNegativeOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? fallback : value;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : left.max(right);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal executionMoney(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private String valueText(BigDecimal value) {
        return value == null ? "待复核" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private record SignalSnapshot(boolean confirmed, List<String> evidence) {
    }

    private record TradeExit(int exitIndex, BigDecimal exitPrice, BigDecimal maxDrawdownPercent, String exitReason) {
    }

    private record OvernightSymbolOutcome(
            OvernightBacktestSymbolResult result,
            List<OvernightBacktestTrade> trades
    ) {
    }

    private record OvernightBaseExitLeg(
            int exitIndex,
            BigDecimal positionRatio,
            BigDecimal baseExitPrice,
            String reason
    ) {
    }

    private record OvernightDayState(
            BigDecimal remaining,
            BigDecimal trailingPeak,
            boolean delayed
    ) {
    }

    private record OvernightExitPlan(
            int finalExitIndex,
            String exitReason,
            boolean firstTargetHit,
            boolean secondTargetHit,
            List<OvernightBaseExitLeg> legs
    ) {
    }
}

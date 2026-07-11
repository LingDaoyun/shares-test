package com.aistock.research.backtest;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final EastMoneyClient eastMoneyClient;

    public BacktestService(EastMoneyClient eastMoneyClient) {
        this.eastMoneyClient = eastMoneyClient;
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

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
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
}

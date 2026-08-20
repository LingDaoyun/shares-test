package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Component
public class ShortTermTechnicalSignalEvaluator {

    private static final BigDecimal MAX_VOLUME_RATIO = new BigDecimal("3.20");
    private static final BigDecimal MIN_MA20_SLOPE = new BigDecimal("-0.20");
    private static final BigDecimal MIN_RANGE60 = new BigDecimal("35");
    private static final BigDecimal MAX_RANGE60 = new BigDecimal("92");

    private final ShortTermGoldenCrossAnalyzer goldenCrossAnalyzer;

    public ShortTermTechnicalSignalEvaluator() {
        this(new ShortTermGoldenCrossAnalyzer());
    }

    @Autowired
    ShortTermTechnicalSignalEvaluator(ShortTermGoldenCrossAnalyzer goldenCrossAnalyzer) {
        this.goldenCrossAnalyzer = goldenCrossAnalyzer;
    }

    public ShortTermTechnicalSignalEvaluation evaluate(
            List<EastMoneyKLine> source,
            BigDecimal evaluationClose,
            boolean latestBarCompleted,
            ShortTermRuleSet ruleSet
    ) {
        List<EastMoneyKLine> rows = source == null ? List.of() : source.stream()
                .filter(row -> row != null && row.close() != null && row.tradeDate() != null)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (rows.size() < 20) {
            return new ShortTermTechnicalSignalEvaluation(
                    rows,
                    unavailableSnapshot(),
                    rows.isEmpty() ? null : rows.get(rows.size() - 1),
                    rows.size() < 2 ? null : rows.get(rows.size() - 2),
                    List.of("近一年 K 线不足，不能确认右侧启动")
            );
        }

        EastMoneyKLine last = rows.get(rows.size() - 1);
        EastMoneyKLine previous = rows.get(rows.size() - 2);
        List<EastMoneyKLine> previousRows = rows.subList(0, rows.size() - 1);
        BigDecimal close = evaluationClose != null && evaluationClose.compareTo(BigDecimal.ZERO) > 0
                ? evaluationClose
                : last.close();
        BigDecimal ma5 = movingAverage(rows, 5);
        BigDecimal ma10 = movingAverage(rows, 10);
        BigDecimal ma20 = movingAverage(rows, 20);
        BigDecimal ma60 = movingAverage(rows, 60);
        BigDecimal ma20Slope = movingAverageSlope(rows, 20, 5);
        BigDecimal ma60Slope = movingAverageSlope(rows, 60, 10);
        BigDecimal previousHigh20 = high(previousRows, 20);
        BigDecimal previousHigh60 = high(previousRows, 60);
        BigDecimal previousLow20 = low(previousRows, 20);
        BigDecimal high120 = high(rows, 120);
        BigDecimal low120 = low(rows, 120);
        BigDecimal low60 = low(rows, 60);
        BigDecimal high60 = high(rows, 60);
        BigDecimal volumeRatio5 = volumeRatio(rows, 5);
        BigDecimal volumeRatio20 = volumeRatio(rows, 20);
        VolumeComparison volumeComparison = threeDayVolumeComparison(rows);
        BigDecimal range60 = rangePosition(close, low60, high60);
        BigDecimal range120 = rangePosition(close, low120, high120);
        BigDecimal distanceToMa20 = percent(close.subtract(nullToZero(ma20)), ma20);
        BigDecimal breakoutFromPreviousHigh20 = percent(close.subtract(nullToZero(previousHigh20)), previousHigh20);
        BigDecimal previousRange20 = percent(nullToZero(previousHigh20).subtract(nullToZero(previousLow20)), previousLow20);
        BigDecimal drawdownFromHigh120 = percent(nullToZero(high120).subtract(close), high120);
        BigDecimal amplitude = last.high() == null || last.low() == null
                ? null
                : percent(last.high().subtract(last.low()), close);
        List<EastMoneyKLine> completedRows = latestBarCompleted
                ? rows
                : rows.subList(0, rows.size() - 1);
        BigDecimal atr14Percent = atr14Percent(completedRows);
        BigDecimal recentSupportPrice = low(completedRows, 20);
        int consecutiveAboveMa20 = consecutiveAboveMa(rows, 20);
        ShortTermGoldenCrossSnapshot goldenCross = goldenCrossAnalyzer.analyze(rows, latestBarCompleted);
        String rightSideSignal = rightSideSignal(
                close,
                previous,
                ma5,
                ma10,
                ma20,
                ma60,
                ma20Slope,
                previousHigh20,
                range60,
                distanceToMa20,
                breakoutFromPreviousHigh20,
                volumeRatio20,
                ruleSet,
                goldenCross
        );
        ShortTermTechnicalSnapshot snapshot = new ShortTermTechnicalSnapshot(
                last.tradeDate(),
                scale(ma5),
                scale(ma10),
                scale(ma20),
                scale(ma60),
                scale(ma20Slope),
                scale(ma60Slope),
                scale(previousHigh20),
                scale(previousHigh60),
                scale(breakoutFromPreviousHigh20),
                scale(previousRange20),
                scale(high120),
                scale(low120),
                scale(volumeRatio5),
                scale(volumeRatio20),
                scale(range60),
                scale(range120),
                scale(distanceToMa20),
                scale(drawdownFromHigh120),
                scale(amplitude),
                consecutiveAboveMa20,
                rightSideSignal,
                goldenCross,
                scale(atr14Percent),
                scale(recentSupportPrice)
        ).withVolumeComparison(
                scale(volumeComparison.todayVolume()),
                scale(volumeComparison.averageVolume3()),
                scale(volumeComparison.volumeRatio3())
        );
        return new ShortTermTechnicalSignalEvaluation(rows, snapshot, last, previous, List.of());
    }

    private ShortTermTechnicalSnapshot unavailableSnapshot() {
        return new ShortTermTechnicalSnapshot(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                0, "K线不足", ShortTermGoldenCrossSnapshot.unavailable(), null, null
        );
    }

    private String rightSideSignal(
            BigDecimal close,
            EastMoneyKLine previous,
            BigDecimal ma5,
            BigDecimal ma10,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma20SlopePercent,
            BigDecimal previousHigh20,
            BigDecimal range60,
            BigDecimal distanceToMa20,
            BigDecimal breakoutFromPreviousHigh20Percent,
            BigDecimal volumeRatio20,
            ShortTermRuleSet ruleSet,
            ShortTermGoldenCrossSnapshot goldenCross
    ) {
        if (close == null || ma20 == null) {
            return "K线不足";
        }
        boolean aboveMa20 = close.compareTo(ma20) > 0;
        boolean ma5AboveMa10 = ma5 != null && ma10 != null && ma5.compareTo(ma10) >= 0;
        boolean ma20Turning = ma20SlopePercent != null && ma20SlopePercent.compareTo(MIN_MA20_SLOPE) >= 0;
        boolean nearMa20 = distanceToMa20 != null
                && distanceToMa20.compareTo(BigDecimal.ZERO) >= 0
                && distanceToMa20.compareTo(ruleSet.maxDistanceToMa20Percent()) <= 0;
        boolean middleRange = range60 != null
                && range60.compareTo(MIN_RANGE60) >= 0
                && range60.compareTo(MAX_RANGE60) <= 0;
        boolean volumeConfirmed = volumeRatio20 != null
                && volumeRatio20.compareTo(ruleSet.minVolumeRatio()) >= 0
                && volumeRatio20.compareTo(MAX_VOLUME_RATIO) <= 0;
        boolean crossedMa20 = previous != null
                && previous.close() != null
                && previous.close().compareTo(ma20) <= 0
                && aboveMa20;
        boolean breakout20 = previousHigh20 != null && close.compareTo(previousHigh20) >= 0
                || breakoutFromPreviousHigh20Percent != null
                && breakoutFromPreviousHigh20Percent.compareTo(BigDecimal.ZERO) >= 0;
        boolean aboveMa60 = ma60 != null && close.compareTo(ma60) > 0;
        boolean approachingGoldenCross = goldenCross != null && "APPROACHING".equals(goldenCross.state());

        if (aboveMa20 && ma5AboveMa10 && ma20Turning && nearMa20 && middleRange
                && volumeConfirmed && (crossedMa20 || breakout20)) {
            return "右侧早期确认";
        }
        if (approachingGoldenCross && aboveMa20 && ma20Turning && nearMa20 && middleRange) {
            return "右侧早期观察";
        }
        if (aboveMa20 && ma5AboveMa10 && ma20Turning && nearMa20 && middleRange) {
            return "右侧早期观察";
        }
        if (aboveMa20 && aboveMa60 && !nearMa20) {
            return "右侧已拉开";
        }
        if (aboveMa20) {
            return "右侧雏形";
        }
        return "尚未右侧";
    }

    private BigDecimal atr14Percent(List<EastMoneyKLine> rows) {
        if (rows == null || rows.size() < 15) {
            return null;
        }
        List<BigDecimal> trueRanges = new java.util.ArrayList<>();
        for (int index = Math.max(1, rows.size() - 14); index < rows.size(); index++) {
            BigDecimal trueRange = trueRange(rows.get(index), rows.get(index - 1));
            if (trueRange != null) {
                trueRanges.add(trueRange);
            }
        }
        BigDecimal averageTrueRange = average(trueRanges);
        BigDecimal latestClose = rows.get(rows.size() - 1).close();
        return averageTrueRange == null ? null : percent(averageTrueRange, latestClose);
    }

    private BigDecimal trueRange(EastMoneyKLine current, EastMoneyKLine previous) {
        if (current == null || previous == null || current.high() == null || current.low() == null || previous.close() == null) {
            return null;
        }
        return current.high().subtract(current.low()).abs()
                .max(current.high().subtract(previous.close()).abs())
                .max(current.low().subtract(previous.close()).abs());
    }

    private BigDecimal movingAverage(List<EastMoneyKLine> rows, int window) {
        List<EastMoneyKLine> slice = lastRows(rows, window);
        return slice.size() < window ? null : average(slice.stream().map(EastMoneyKLine::close).toList());
    }

    private BigDecimal movingAverageSlope(List<EastMoneyKLine> rows, int window, int lookbackDays) {
        if (rows == null || rows.size() <= window + lookbackDays) {
            return null;
        }
        BigDecimal current = movingAverage(rows, window);
        BigDecimal previous = movingAverage(rows.subList(0, rows.size() - lookbackDays), window);
        return current == null || previous == null || previous.signum() == 0
                ? null
                : percent(current.subtract(previous), previous);
    }

    private BigDecimal volumeRatio(List<EastMoneyKLine> rows, int window) {
        if (rows.size() <= window) {
            return null;
        }
        EastMoneyKLine last = rows.get(rows.size() - 1);
        if (last.volume() == null || last.volume().signum() <= 0) {
            return null;
        }
        BigDecimal averageVolume = average(lastRows(rows.subList(0, rows.size() - 1), window)
                .stream().map(EastMoneyKLine::volume).toList());
        return averageVolume == null || averageVolume.signum() <= 0
                ? null
                : last.volume().divide(averageVolume, 4, RoundingMode.HALF_UP);
    }

    private VolumeComparison threeDayVolumeComparison(List<EastMoneyKLine> rows) {
        if (rows == null || rows.size() < 4) {
            return VolumeComparison.unavailable();
        }
        int size = rows.size();
        BigDecimal today = rows.get(size - 1).volume();
        List<BigDecimal> previousVolumes = rows.subList(size - 4, size - 1).stream()
                .map(EastMoneyKLine::volume)
                .toList();
        if (!positive(today) || previousVolumes.stream().anyMatch(value -> !positive(value))) {
            return VolumeComparison.unavailable();
        }
        BigDecimal averageVolume3 = previousVolumes.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
        BigDecimal volumeRatio3 = today.divide(averageVolume3, 6, RoundingMode.HALF_UP);
        return new VolumeComparison(today, averageVolume3, volumeRatio3);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
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

    private int consecutiveAboveMa(List<EastMoneyKLine> rows, int window) {
        int count = 0;
        for (int index = rows.size() - 1; index >= window - 1; index--) {
            BigDecimal ma = movingAverage(rows.subList(0, index + 1), window);
            if (ma == null || rows.get(index).close().compareTo(ma) <= 0) {
                break;
            }
            count++;
        }
        return count;
    }

    private BigDecimal rangePosition(BigDecimal close, BigDecimal low, BigDecimal high) {
        return close == null || low == null || high == null || high.compareTo(low) <= 0
                ? null
                : close.subtract(low).divide(high.subtract(low), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> valid = values.stream().filter(value -> value != null).toList();
        return valid.isEmpty()
                ? null
                : valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(valid.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() == 0
                ? null
                : numerator.divide(denominator, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record VolumeComparison(
            BigDecimal todayVolume,
            BigDecimal averageVolume3,
            BigDecimal volumeRatio3
    ) {
        private static VolumeComparison unavailable() {
            return new VolumeComparison(null, null, null);
        }
    }
}

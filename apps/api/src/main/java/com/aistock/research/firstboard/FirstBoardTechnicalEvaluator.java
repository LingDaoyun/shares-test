package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Component
public class FirstBoardTechnicalEvaluator {

    private static final int MINIMUM_BARS = 60;
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    Optional<FirstBoardBaseline> evaluate(
            String symbol,
            List<EastMoneyKLine> source,
            LocalDate baselineDate,
            boolean previousLimitUp
    ) {
        return evaluate(symbol, null, null, FirstBoardBoard.fromSymbol(symbol), source, baselineDate, previousLimitUp);
    }

    Optional<FirstBoardBaseline> evaluate(
            String symbol,
            String name,
            String industry,
            FirstBoardBoard board,
            List<EastMoneyKLine> source,
            LocalDate baselineDate,
            boolean previousLimitUp
    ) {
        List<EastMoneyKLine> rows = normalized(source);
        if (rows.size() < MINIMUM_BARS || baselineDate == null
                || !baselineDate.equals(rows.get(rows.size() - 1).tradeDate())) {
            return Optional.empty();
        }
        EastMoneyKLine last = rows.get(rows.size() - 1);
        if (!validBar(last)) {
            return Optional.empty();
        }

        BigDecimal ma10 = averageClose(rows, rows.size(), 10);
        BigDecimal ma20 = averageClose(rows, rows.size(), 20);
        BigDecimal previousMa20 = averageClose(rows, rows.size() - 5, 20);
        BigDecimal ma20Slope = percentChange(ma20, previousMa20);
        BigDecimal rsi14 = rsi(rows, 14);
        BigDecimal macdHistogram = macdHistogram(rows);
        BigDecimal previousDayVolumeRatio = volumeRatio(rows, rows.size() - 1);
        BigDecimal recentFiveMaxVolumeRatio = recentFiveMaxVolumeRatio(rows);
        BigDecimal upperShadowRatio = percent(
                last.high().subtract(last.open().max(last.close()), MC),
                last.close()
        );
        BigDecimal threeDayCloseRange = closeRange(rows, 3);
        if (ma10 == null || ma20 == null || previousMa20 == null || rsi14 == null
                || macdHistogram == null || previousDayVolumeRatio == null
                || recentFiveMaxVolumeRatio == null || upperShadowRatio == null
                || threeDayCloseRange == null) {
            return Optional.empty();
        }

        int passCount = 0;
        if (last.close().compareTo(ma20) > 0) passCount++;
        if (ma10.compareTo(ma20) > 0) passCount++;
        if (ma20Slope.signum() > 0) passCount++;
        if (rsi14.compareTo(new BigDecimal("50")) >= 0) passCount++;
        if (macdHistogram.signum() > 0) passCount++;

        List<FirstBoardBar> recentBars = rows.subList(Math.max(0, rows.size() - 20), rows.size()).stream()
                .map(row -> new FirstBoardBar(
                        row.tradeDate(), row.open(), row.close(), row.high(), row.low(), row.volume()))
                .toList();
        return Optional.of(new FirstBoardBaseline(
                symbol, name, industry, board, baselineDate, scale(last.close()),
                scale(ma10), scale(ma20), scale(ma20Slope), scale(rsi14), scale(macdHistogram), passCount,
                scale(previousDayVolumeRatio), scale(recentFiveMaxVolumeRatio), scale(upperShadowRatio),
                scale(threeDayCloseRange), previousLimitUp, recentBars
        ));
    }

    private List<EastMoneyKLine> normalized(List<EastMoneyKLine> source) {
        Map<LocalDate, EastMoneyKLine> byDate = new TreeMap<>();
        if (source != null) {
            source.stream()
                    .filter(this::validBar)
                    .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                    .forEach(row -> byDate.put(row.tradeDate(), row));
        }
        return List.copyOf(byDate.values());
    }

    private boolean validBar(EastMoneyKLine row) {
        return row != null && row.tradeDate() != null && positive(row.open()) && positive(row.close())
                && positive(row.high()) && positive(row.low()) && row.volume() != null && row.volume().signum() >= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal averageClose(List<EastMoneyKLine> rows, int endExclusive, int window) {
        if (endExclusive < window || endExclusive > rows.size()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (int index = endExclusive - window; index < endExclusive; index++) {
            total = total.add(rows.get(index).close(), MC);
        }
        return total.divide(BigDecimal.valueOf(window), MC);
    }

    private BigDecimal rsi(List<EastMoneyKLine> rows, int period) {
        if (rows.size() <= period) return null;
        BigDecimal averageGain = BigDecimal.ZERO;
        BigDecimal averageLoss = BigDecimal.ZERO;
        for (int index = 1; index <= period; index++) {
            BigDecimal change = rows.get(index).close().subtract(rows.get(index - 1).close(), MC);
            if (change.signum() > 0) averageGain = averageGain.add(change, MC);
            if (change.signum() < 0) averageLoss = averageLoss.add(change.abs(), MC);
        }
        averageGain = averageGain.divide(BigDecimal.valueOf(period), MC);
        averageLoss = averageLoss.divide(BigDecimal.valueOf(period), MC);
        for (int index = period + 1; index < rows.size(); index++) {
            BigDecimal change = rows.get(index).close().subtract(rows.get(index - 1).close(), MC);
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;
            averageGain = averageGain.multiply(BigDecimal.valueOf(period - 1L), MC)
                    .add(gain, MC).divide(BigDecimal.valueOf(period), MC);
            averageLoss = averageLoss.multiply(BigDecimal.valueOf(period - 1L), MC)
                    .add(loss, MC).divide(BigDecimal.valueOf(period), MC);
        }
        if (averageGain.signum() == 0 && averageLoss.signum() == 0) return new BigDecimal("50");
        if (averageLoss.signum() == 0) return HUNDRED;
        BigDecimal relativeStrength = averageGain.divide(averageLoss, MC);
        return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(relativeStrength, MC), MC), MC);
    }

    private BigDecimal macdHistogram(List<EastMoneyKLine> rows) {
        BigDecimal ema12 = rows.get(0).close();
        BigDecimal ema26 = rows.get(0).close();
        BigDecimal signal = BigDecimal.ZERO;
        BigDecimal dif = BigDecimal.ZERO;
        for (int index = 1; index < rows.size(); index++) {
            BigDecimal close = rows.get(index).close();
            ema12 = ema(close, ema12, 12);
            ema26 = ema(close, ema26, 26);
            dif = ema12.subtract(ema26, MC);
            signal = ema(dif, signal, 9);
        }
        return dif.subtract(signal, MC).multiply(BigDecimal.valueOf(2), MC);
    }

    private BigDecimal ema(BigDecimal value, BigDecimal previous, int period) {
        BigDecimal alpha = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), MC);
        return value.multiply(alpha, MC).add(previous.multiply(BigDecimal.ONE.subtract(alpha, MC), MC), MC);
    }

    private BigDecimal volumeRatio(List<EastMoneyKLine> rows, int index) {
        if (index < 5 || index >= rows.size()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (int cursor = index - 5; cursor < index; cursor++) {
            total = total.add(rows.get(cursor).volume(), MC);
        }
        BigDecimal average = total.divide(BigDecimal.valueOf(5), MC);
        return average.signum() <= 0 ? null : rows.get(index).volume().divide(average, MC);
    }

    private BigDecimal recentFiveMaxVolumeRatio(List<EastMoneyKLine> rows) {
        List<BigDecimal> ratios = new ArrayList<>();
        for (int index = Math.max(5, rows.size() - 5); index < rows.size(); index++) {
            BigDecimal ratio = volumeRatio(rows, index);
            if (ratio != null) ratios.add(ratio);
        }
        return ratios.stream().max(BigDecimal::compareTo).orElse(null);
    }

    private BigDecimal closeRange(List<EastMoneyKLine> rows, int window) {
        if (rows.size() < window) return null;
        List<BigDecimal> closes = rows.subList(rows.size() - window, rows.size()).stream()
                .map(EastMoneyKLine::close)
                .toList();
        BigDecimal minimum = closes.stream().min(BigDecimal::compareTo).orElse(null);
        BigDecimal maximum = closes.stream().max(BigDecimal::compareTo).orElse(null);
        return minimum == null || minimum.signum() <= 0 ? null : percent(maximum.subtract(minimum, MC), minimum);
    }

    private BigDecimal percentChange(BigDecimal current, BigDecimal base) {
        return current == null || base == null ? null : percent(current.subtract(base, MC), base);
    }

    private BigDecimal percent(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() <= 0) return null;
        return value.multiply(HUNDRED, MC).divide(base, MC);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}

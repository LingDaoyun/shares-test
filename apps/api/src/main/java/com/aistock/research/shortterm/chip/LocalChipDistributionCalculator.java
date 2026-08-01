package com.aistock.research.shortterm.chip;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class LocalChipDistributionCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_BUCKET_STEP = new BigDecimal("0.01");
    private static final MathContext MC = MathContext.DECIMAL64;

    private final int lookbackBars;
    private final int priceBuckets;
    private final int minValidBars;
    private final BigDecimal minTurnoverCoverage;

    public LocalChipDistributionCalculator(
            int lookbackBars,
            int priceBuckets,
            int minValidBars,
            BigDecimal minTurnoverCoverage
    ) {
        if (lookbackBars < 1 || priceBuckets < 2 || minValidBars < 1) {
            throw new IllegalArgumentException("筹码计算参数必须为正数");
        }
        this.lookbackBars = lookbackBars;
        this.priceBuckets = priceBuckets;
        this.minValidBars = minValidBars;
        this.minTurnoverCoverage = minTurnoverCoverage == null
                ? new BigDecimal("0.95")
                : minTurnoverCoverage;
    }

    public LocalChipDistribution calculate(
            List<EastMoneyKLine> input,
            BigDecimal currentPrice,
            ChipCalculationMode mode
    ) {
        ChipCalculationMode calculationMode = mode == null
                ? ChipCalculationMode.COMPLETED_BAR
                : mode;
        List<String> gaps = new ArrayList<>();
        List<EastMoneyKLine> bars = validBars(input).stream()
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (bars.size() > lookbackBars) {
            bars = bars.subList(bars.size() - lookbackBars, bars.size());
        }
        LocalDate tradeDate = bars.isEmpty() ? null : bars.get(bars.size() - 1).tradeDate();
        BigDecimal coverage = turnoverCoverage(bars);
        if (bars.size() < minValidBars) {
            gaps.add("有效K线少于" + minValidBars + "日");
        }
        if (coverage.movePointLeft(2).compareTo(minTurnoverCoverage) < 0) {
            gaps.add("换手率有效覆盖低于95%");
        }
        if (!positive(currentPrice)) {
            gaps.add("当前价格无效");
        }
        if (!gaps.isEmpty()) {
            return LocalChipDistribution.insufficient(
                    calculationMode, tradeDate, bars.size(), scaleRatio(coverage), gaps);
        }

        BigDecimal minimum = bars.stream().map(EastMoneyKLine::low).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal maximum = bars.stream().map(EastMoneyKLine::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal step = maximum.subtract(minimum)
                .divide(BigDecimal.valueOf(priceBuckets - 1L), 10, RoundingMode.HALF_UP)
                .max(MIN_BUCKET_STEP);
        BigDecimal[] prices = new BigDecimal[priceBuckets];
        double[] weights = new double[priceBuckets];
        for (int index = 0; index < priceBuckets; index++) {
            prices[index] = minimum.add(step.multiply(BigDecimal.valueOf(index)));
        }

        boolean clampedTurnover = false;
        boolean missingTurnover = false;
        for (EastMoneyKLine bar : bars) {
            if (bar.turnoverRate() == null) {
                missingTurnover = true;
                continue;
            }
            double rawTurnover = bar.turnoverRate().doubleValue() / 100d;
            double turnover = Math.max(0d, Math.min(1d, rawTurnover));
            clampedTurnover |= rawTurnover < 0d || rawTurnover > 1d;
            double existingMass = Arrays.stream(weights).sum();
            double newMass = existingMass <= 0d ? 1d : turnover;
            if (existingMass > 0d) {
                for (int index = 0; index < weights.length; index++) {
                    weights[index] *= 1d - turnover;
                }
            }
            allocateDailyChips(weights, prices, bar, averageTradePrice(bar), newMass);
        }
        if (clampedTurnover) {
            gaps.add("异常换手率已按0%-100%截断");
        }
        if (missingTurnover) {
            gaps.add("少量换手率缺失日未注入新筹码");
        }
        normalize(weights);
        return summarize(bars, prices, weights, currentPrice, calculationMode, coverage, gaps);
    }

    private List<EastMoneyKLine> validBars(List<EastMoneyKLine> input) {
        if (input == null) {
            return List.of();
        }
        return input.stream()
                .filter(this::validBar)
                .toList();
    }

    private boolean validBar(EastMoneyKLine bar) {
        if (bar == null || bar.tradeDate() == null
                || !positive(bar.open()) || !positive(bar.close())
                || !positive(bar.high()) || !positive(bar.low())) {
            return false;
        }
        return bar.low().compareTo(bar.open()) <= 0
                && bar.low().compareTo(bar.close()) <= 0
                && bar.high().compareTo(bar.open()) >= 0
                && bar.high().compareTo(bar.close()) >= 0
                && bar.high().compareTo(bar.low()) >= 0;
    }

    private BigDecimal turnoverCoverage(List<EastMoneyKLine> bars) {
        if (bars.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long available = bars.stream().filter(bar -> bar.turnoverRate() != null).count();
        return BigDecimal.valueOf(available)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(bars.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal averageTradePrice(EastMoneyKLine bar) {
        if (positive(bar.amount()) && positive(bar.volume())) {
            BigDecimal direct = bar.amount().divide(bar.volume(), 8, RoundingMode.HALF_UP);
            if (inside(direct, bar.low(), bar.high())) {
                return direct;
            }
            BigDecimal perHundred = direct.divide(HUNDRED, 8, RoundingMode.HALF_UP);
            if (inside(perHundred, bar.low(), bar.high())) {
                return perHundred;
            }
        }
        return bar.open().add(bar.close()).add(bar.high()).add(bar.low())
                .divide(BigDecimal.valueOf(4), 8, RoundingMode.HALF_UP);
    }

    private void allocateDailyChips(
            double[] weights,
            BigDecimal[] prices,
            EastMoneyKLine bar,
            BigDecimal averagePrice,
            double mass
    ) {
        if (mass <= 0d) {
            return;
        }
        if (bar.high().compareTo(bar.low()) == 0) {
            weights[nearestPriceIndex(prices, bar.close())] += mass;
            return;
        }
        double[] daily = new double[prices.length];
        double total = 0d;
        double low = bar.low().doubleValue();
        double high = bar.high().doubleValue();
        double peak = Math.max(low, Math.min(high, averagePrice.doubleValue()));
        for (int index = 0; index < prices.length; index++) {
            double price = prices[index].doubleValue();
            if (price < low || price > high) {
                continue;
            }
            double value;
            if (Math.abs(high - low) < 1e-9) {
                value = 1d;
            } else if (price <= peak) {
                value = Math.abs(peak - low) < 1e-9 ? 1d : (price - low) / (peak - low);
            } else {
                value = Math.abs(high - peak) < 1e-9 ? 1d : (high - price) / (high - peak);
            }
            daily[index] = Math.max(0d, value);
            total += daily[index];
        }
        if (total <= 0d) {
            weights[nearestPriceIndex(prices, averagePrice)] += mass;
            return;
        }
        for (int index = 0; index < weights.length; index++) {
            weights[index] += mass * daily[index] / total;
        }
    }

    private LocalChipDistribution summarize(
            List<EastMoneyKLine> bars,
            BigDecimal[] prices,
            double[] weights,
            BigDecimal currentPrice,
            ChipCalculationMode mode,
            BigDecimal coverage,
            List<String> gaps
    ) {
        BigDecimal average = weightedAverage(prices, weights);
        BigDecimal cost5 = percentile(prices, weights, 0.05d);
        BigDecimal cost15 = percentile(prices, weights, 0.15d);
        BigDecimal cost50 = percentile(prices, weights, 0.50d);
        BigDecimal cost85 = percentile(prices, weights, 0.85d);
        BigDecimal cost95 = percentile(prices, weights, 0.95d);
        BigDecimal winnerRate = percentAtOrBelow(prices, weights, currentPrice);
        BigDecimal overhead = HUNDRED.subtract(winnerRate).max(BigDecimal.ZERO);
        BigDecimal cost70Width = percentWidth(cost15, cost85, average);
        BigDecimal cost90Width = percentWidth(cost5, cost95, average);
        BigDecimal distance = currentPrice.subtract(average)
                .multiply(HUNDRED)
                .divide(average, 6, RoundingMode.HALF_UP);
        PriorHigh priorHigh = priorHigh(bars, prices, weights);

        return new LocalChipDistribution(
                ChipDataQuality.VALID,
                mode,
                bars.get(bars.size() - 1).tradeDate(),
                scalePrice(average),
                scalePrice(cost5),
                scalePrice(cost15),
                scalePrice(cost50),
                scalePrice(cost85),
                scalePrice(cost95),
                scaleRatio(winnerRate),
                scaleRatio(overhead),
                scalePrice(cost15),
                scalePrice(cost85),
                scaleRatio(cost70Width),
                scalePrice(cost5),
                scalePrice(cost95),
                scaleRatio(cost90Width),
                scaleRatio(distance),
                scalePrice(priorHigh.price()),
                scaleRatio(priorHigh.residualPercent()),
                scaleRatio(priorHigh.turnoverSincePercent()),
                scaleRatio(coverage),
                bars.size(),
                gaps
        );
    }

    private PriorHigh priorHigh(List<EastMoneyKLine> bars, BigDecimal[] prices, double[] weights) {
        if (bars.size() <= 5) {
            return new PriorHigh(null, null, null);
        }
        int priorHighIndex = 0;
        for (int index = 1; index < bars.size() - 5; index++) {
            if (bars.get(index).high().compareTo(bars.get(priorHighIndex).high()) > 0) {
                priorHighIndex = index;
            }
        }
        BigDecimal priorHigh = bars.get(priorHighIndex).high();
        BigDecimal zoneLow = priorHigh.multiply(new BigDecimal("0.90"));
        double residual = 0d;
        for (int index = 0; index < prices.length; index++) {
            if (prices[index].compareTo(zoneLow) >= 0 && prices[index].compareTo(priorHigh) <= 0) {
                residual += weights[index];
            }
        }
        BigDecimal turnover = bars.subList(priorHighIndex + 1, bars.size()).stream()
                .map(EastMoneyKLine::turnoverRate)
                .filter(java.util.Objects::nonNull)
                .map(value -> value.max(BigDecimal.ZERO).min(HUNDRED))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PriorHigh(priorHigh, BigDecimal.valueOf(residual * 100d), turnover);
    }

    private BigDecimal weightedAverage(BigDecimal[] prices, double[] weights) {
        BigDecimal result = BigDecimal.ZERO;
        for (int index = 0; index < prices.length; index++) {
            result = result.add(prices[index].multiply(BigDecimal.valueOf(weights[index]), MC));
        }
        return result;
    }

    private BigDecimal percentile(BigDecimal[] prices, double[] weights, double target) {
        double cumulative = 0d;
        for (int index = 0; index < weights.length; index++) {
            cumulative += weights[index];
            if (cumulative + 1e-9 >= target) {
                return prices[index];
            }
        }
        return prices[prices.length - 1];
    }

    private BigDecimal percentAtOrBelow(BigDecimal[] prices, double[] weights, BigDecimal currentPrice) {
        double total = 0d;
        for (int index = 0; index < weights.length; index++) {
            if (prices[index].compareTo(currentPrice) <= 0) {
                total += weights[index];
            }
        }
        return BigDecimal.valueOf(total * 100d);
    }

    private BigDecimal percentWidth(BigDecimal low, BigDecimal high, BigDecimal average) {
        return high.subtract(low).max(BigDecimal.ZERO)
                .multiply(HUNDRED)
                .divide(average, 6, RoundingMode.HALF_UP);
    }

    private void normalize(double[] weights) {
        double total = Arrays.stream(weights).sum();
        if (total <= 0d) {
            return;
        }
        for (int index = 0; index < weights.length; index++) {
            weights[index] /= total;
        }
    }

    private int nearestPriceIndex(BigDecimal[] prices, BigDecimal target) {
        int nearest = 0;
        BigDecimal distance = prices[0].subtract(target).abs();
        for (int index = 1; index < prices.length; index++) {
            BigDecimal candidate = prices[index].subtract(target).abs();
            if (candidate.compareTo(distance) < 0) {
                nearest = index;
                distance = candidate;
            }
        }
        return nearest;
    }

    private boolean inside(BigDecimal value, BigDecimal low, BigDecimal high) {
        return value != null && value.compareTo(low) >= 0 && value.compareTo(high) <= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal scalePrice(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleRatio(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record PriorHigh(
            BigDecimal price,
            BigDecimal residualPercent,
            BigDecimal turnoverSincePercent
    ) {
    }
}

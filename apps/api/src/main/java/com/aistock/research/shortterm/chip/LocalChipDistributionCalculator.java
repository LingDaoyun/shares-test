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
    private final int displayBuckets;
    private final int maxConcentrationZones;
    private final BigDecimal minPeakRelativeHeight;
    private final BigDecimal zoneEdgeRelativeHeight;

    public LocalChipDistributionCalculator(
            int lookbackBars,
            int priceBuckets,
            int minValidBars,
            BigDecimal minTurnoverCoverage
    ) {
        this(
                lookbackBars,
                priceBuckets,
                minValidBars,
                minTurnoverCoverage,
                60,
                3,
                new BigDecimal("0.20"),
                new BigDecimal("0.25")
        );
    }

    public LocalChipDistributionCalculator(
            int lookbackBars,
            int priceBuckets,
            int minValidBars,
            BigDecimal minTurnoverCoverage,
            int displayBuckets,
            int maxConcentrationZones,
            BigDecimal minPeakRelativeHeight,
            BigDecimal zoneEdgeRelativeHeight
    ) {
        if (lookbackBars < 1 || priceBuckets < 2 || minValidBars < 1
                || displayBuckets < 1 || maxConcentrationZones < 1) {
            throw new IllegalArgumentException("筹码计算参数必须为正数");
        }
        this.lookbackBars = lookbackBars;
        this.priceBuckets = priceBuckets;
        this.minValidBars = minValidBars;
        this.minTurnoverCoverage = minTurnoverCoverage == null
                ? new BigDecimal("0.95")
                : minTurnoverCoverage;
        this.displayBuckets = Math.min(displayBuckets, priceBuckets);
        this.maxConcentrationZones = maxConcentrationZones;
        this.minPeakRelativeHeight = boundedFraction(minPeakRelativeHeight, "0.20");
        this.zoneEdgeRelativeHeight = boundedFraction(zoneEdgeRelativeHeight, "0.25");
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
        int effectiveBucketCount = maximum.compareTo(minimum) == 0 ? 1 : priceBuckets;
        BigDecimal step = effectiveBucketCount == 1
                ? MIN_BUCKET_STEP
                : maximum.subtract(minimum)
                .divide(BigDecimal.valueOf(effectiveBucketCount - 1L), 10, RoundingMode.HALF_UP)
                .max(MIN_BUCKET_STEP);
        BigDecimal[] prices = new BigDecimal[effectiveBucketCount];
        double[] weights = new double[effectiveBucketCount];
        for (int index = 0; index < effectiveBucketCount; index++) {
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
        List<ChipDistributionBucket> displayDistribution = displayDistribution(prices, weights);
        List<ChipConcentrationZone> zones = concentrationZones(prices, weights, currentPrice);
        ChipConcentrationZone dominant = zones.isEmpty() ? null : zones.get(0);
        ChipConcentrationZone nearestOverhead = zones.stream()
                .filter(zone -> zone.positionToCurrentPrice() == ChipPricePosition.ABOVE)
                .min(Comparator.comparing(ChipConcentrationZone::lowPrice))
                .orElse(null);

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
                displayDistribution,
                zones,
                dominant == null ? null : dominant.peakPrice(),
                dominant == null ? null : dominant.lowPrice(),
                dominant == null ? null : dominant.highPrice(),
                dominant == null ? null : dominant.chipRatioPercent(),
                currentPricePosition(currentPrice, dominant),
                nearestOverhead,
                gaps
        );
    }

    private List<ChipDistributionBucket> displayDistribution(BigDecimal[] prices, double[] weights) {
        int first = firstPositiveIndex(weights);
        int last = lastPositiveIndex(weights);
        if (first < 0 || last < first) {
            return List.of();
        }
        int activeCount = last - first + 1;
        int groupSize = Math.max(1, (int) Math.ceil((double) activeCount / displayBuckets));
        List<DisplayGroup> groups = new ArrayList<>();
        double maxRatio = 0d;
        for (int start = first; start <= last; start += groupSize) {
            int end = Math.min(last, start + groupSize - 1);
            double ratio = 0d;
            BigDecimal weightedPrice = BigDecimal.ZERO;
            for (int index = start; index <= end; index++) {
                ratio += weights[index];
                weightedPrice = weightedPrice.add(
                        prices[index].multiply(BigDecimal.valueOf(weights[index]), MC));
            }
            BigDecimal representative = ratio <= 0d
                    ? prices[start].add(prices[end]).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP)
                    : weightedPrice.divide(BigDecimal.valueOf(ratio), 8, RoundingMode.HALF_UP);
            groups.add(new DisplayGroup(start, end, representative, ratio));
            maxRatio = Math.max(maxRatio, ratio);
        }
        List<ChipDistributionBucket> result = new ArrayList<>();
        for (DisplayGroup group : groups) {
            BigDecimal normalized = maxRatio <= 0d
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(group.ratio() / maxRatio * 100d);
            result.add(new ChipDistributionBucket(
                    scalePrice(prices[group.start()]),
                    scalePrice(prices[group.end()]),
                    scalePrice(group.price()),
                    scaleRatio(BigDecimal.valueOf(group.ratio() * 100d)),
                    scaleRatio(normalized)
            ));
        }
        return List.copyOf(result);
    }

    private List<ChipConcentrationZone> concentrationZones(
            BigDecimal[] prices,
            double[] weights,
            BigDecimal currentPrice
    ) {
        double maxWeight = Arrays.stream(weights).max().orElse(0d);
        if (maxWeight <= 0d) {
            return List.of();
        }
        double peakFloor = maxWeight * minPeakRelativeHeight.doubleValue();
        List<PeakRange> candidates = new ArrayList<>();
        for (int index = 0; index < weights.length; index++) {
            double left = index == 0 ? Double.NEGATIVE_INFINITY : weights[index - 1];
            double right = index == weights.length - 1 ? Double.NEGATIVE_INFINITY : weights[index + 1];
            if (weights[index] + 1e-12 < peakFloor
                    || weights[index] + 1e-12 < left
                    || weights[index] + 1e-12 < right) {
                continue;
            }
            candidates.add(expandPeak(index, weights));
        }
        candidates.sort(Comparator
                .comparingDouble(PeakRange::peakWeight).reversed()
                .thenComparingInt(PeakRange::peakIndex));

        List<PeakRange> selected = new ArrayList<>();
        for (PeakRange candidate : candidates) {
            boolean overlaps = selected.stream().anyMatch(existing -> rangesOverlap(candidate, existing));
            if (!overlaps) {
                selected.add(candidate);
            }
            if (selected.size() >= maxConcentrationZones) {
                break;
            }
        }
        selected.sort(Comparator.comparingInt(PeakRange::peakIndex));
        splitOverlapsAtValleys(selected, weights);

        List<UnrankedZone> unranked = new ArrayList<>();
        for (PeakRange range : selected) {
            double ratio = 0d;
            for (int index = range.start(); index <= range.end(); index++) {
                ratio += weights[index];
            }
            ChipPricePosition position = zonePosition(
                    prices[range.start()], prices[range.end()], currentPrice);
            BigDecimal distance = prices[range.peakIndex()].subtract(currentPrice)
                    .multiply(HUNDRED)
                    .divide(currentPrice, 6, RoundingMode.HALF_UP);
            unranked.add(new UnrankedZone(
                    range,
                    scalePrice(prices[range.start()]),
                    scalePrice(prices[range.end()]),
                    scalePrice(prices[range.peakIndex()]),
                    scaleRatio(BigDecimal.valueOf(ratio * 100d)),
                    scaleRatio(distance),
                    position
            ));
        }
        unranked.sort(Comparator
                .comparing(UnrankedZone::chipRatioPercent, Comparator.reverseOrder())
                .thenComparing(UnrankedZone::peakPrice));
        List<ChipConcentrationZone> result = new ArrayList<>();
        for (int index = 0; index < unranked.size(); index++) {
            UnrankedZone zone = unranked.get(index);
            result.add(new ChipConcentrationZone(
                    index + 1,
                    zone.lowPrice(),
                    zone.highPrice(),
                    zone.peakPrice(),
                    zone.chipRatioPercent(),
                    zone.distanceToCurrentPricePercent(),
                    zone.positionToCurrentPrice()
            ));
        }
        return List.copyOf(result);
    }

    private PeakRange expandPeak(int peakIndex, double[] weights) {
        double edge = weights[peakIndex] * zoneEdgeRelativeHeight.doubleValue();
        int start = peakIndex;
        int end = peakIndex;
        while (start > 0 && weights[start - 1] + 1e-12 >= edge) {
            start--;
        }
        while (end < weights.length - 1 && weights[end + 1] + 1e-12 >= edge) {
            end++;
        }
        return new PeakRange(peakIndex, start, end, weights[peakIndex]);
    }

    private boolean rangesOverlap(PeakRange left, PeakRange right) {
        return left.start() <= right.end() && right.start() <= left.end();
    }

    private void splitOverlapsAtValleys(List<PeakRange> ranges, double[] weights) {
        for (int index = 0; index < ranges.size() - 1; index++) {
            PeakRange left = ranges.get(index);
            PeakRange right = ranges.get(index + 1);
            if (!rangesOverlap(left, right)) {
                continue;
            }
            int valley = left.peakIndex();
            for (int cursor = left.peakIndex(); cursor <= right.peakIndex(); cursor++) {
                if (weights[cursor] < weights[valley]) {
                    valley = cursor;
                }
            }
            ranges.set(index, left.withEnd(Math.max(left.peakIndex(), valley)));
            ranges.set(index + 1, right.withStart(Math.min(right.peakIndex(), valley + 1)));
        }
    }

    private ChipPricePosition zonePosition(BigDecimal low, BigDecimal high, BigDecimal currentPrice) {
        if (low.compareTo(currentPrice) > 0) {
            return ChipPricePosition.ABOVE;
        }
        if (high.compareTo(currentPrice) < 0) {
            return ChipPricePosition.BELOW;
        }
        return ChipPricePosition.AROUND;
    }

    private ChipPricePosition currentPricePosition(
            BigDecimal currentPrice,
            ChipConcentrationZone dominant
    ) {
        if (dominant == null) {
            return null;
        }
        if (currentPrice.compareTo(dominant.lowPrice()) < 0) {
            return ChipPricePosition.BELOW;
        }
        if (currentPrice.compareTo(dominant.highPrice()) > 0) {
            return ChipPricePosition.ABOVE;
        }
        return ChipPricePosition.AROUND;
    }

    private int firstPositiveIndex(double[] weights) {
        for (int index = 0; index < weights.length; index++) {
            if (weights[index] > 1e-12) {
                return index;
            }
        }
        return -1;
    }

    private int lastPositiveIndex(double[] weights) {
        for (int index = weights.length - 1; index >= 0; index--) {
            if (weights[index] > 1e-12) {
                return index;
            }
        }
        return -1;
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

    private BigDecimal boundedFraction(BigDecimal value, String fallback) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            return new BigDecimal(fallback);
        }
        return value;
    }

    private record DisplayGroup(int start, int end, BigDecimal price, double ratio) {
    }

    private record PeakRange(int peakIndex, int start, int end, double peakWeight) {
        private PeakRange withStart(int value) {
            return new PeakRange(peakIndex, value, end, peakWeight);
        }

        private PeakRange withEnd(int value) {
            return new PeakRange(peakIndex, start, value, peakWeight);
        }
    }

    private record UnrankedZone(
            PeakRange range,
            BigDecimal lowPrice,
            BigDecimal highPrice,
            BigDecimal peakPrice,
            BigDecimal chipRatioPercent,
            BigDecimal distanceToCurrentPricePercent,
            ChipPricePosition positionToCurrentPrice
    ) {
    }

    private record PriorHigh(
            BigDecimal price,
            BigDecimal residualPercent,
            BigDecimal turnoverSincePercent
    ) {
    }
}

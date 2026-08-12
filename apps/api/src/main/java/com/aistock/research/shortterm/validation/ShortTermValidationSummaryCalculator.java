package com.aistock.research.shortterm.validation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Component
public class ShortTermValidationSummaryCalculator {

    public ShortTermValidationSummary summarize(
            String ruleVersion,
            String signalFamily,
            String marketRegime,
            String horizon,
            int minimumSampleCount,
            List<ShortTermValidationSample> source
    ) {
        int minimum = Math.max(1, minimumSampleCount);
        List<ShortTermValidationSample> samples = (source == null
                ? List.<ShortTermValidationSample>of() : source).stream()
                .filter(sample -> sample != null && sample.netReturnPercent() != null)
                .toList();
        if (samples.size() < minimum) {
            return new ShortTermValidationSummary(
                    ruleVersion, signalFamily, marketRegime, horizon,
                    "INSUFFICIENT_SAMPLE", minimum, samples.size(),
                    null, null, null, null, null
            );
        }
        List<BigDecimal> returns = samples.stream()
                .map(ShortTermValidationSample::netReturnPercent)
                .sorted(Comparator.naturalOrder())
                .toList();
        long positive = returns.stream().filter(value -> value.signum() > 0).count();
        return new ShortTermValidationSummary(
                ruleVersion,
                signalFamily,
                marketRegime,
                horizon,
                "AVAILABLE",
                minimum,
                samples.size(),
                scaled(BigDecimal.valueOf(positive).multiply(new BigDecimal("100"))
                        .divide(BigDecimal.valueOf(samples.size()), 8, RoundingMode.HALF_UP)),
                scaled(average(returns)),
                scaled(median(returns)),
                scaled(average(samples.stream()
                        .map(ShortTermValidationSample::maxFavorableExcursionPercent).toList())),
                scaled(average(samples.stream()
                        .map(ShortTermValidationSample::maxAdverseExcursionPercent).toList()))
        );
    }

    private BigDecimal average(List<BigDecimal> source) {
        List<BigDecimal> values = source == null ? List.of() : source.stream()
                .filter(value -> value != null)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        int size = values.size();
        if (size % 2 == 1) {
            return values.get(size / 2);
        }
        return values.get(size / 2 - 1).add(values.get(size / 2))
                .divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}

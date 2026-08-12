package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class ShortTermVolatilityQualityEvaluator {

    private static final BigDecimal CONTRACTION_LIMIT = new BigDecimal("0.80");
    private static final BigDecimal EXPANSION_CONFIRMATION = new BigDecimal("1.15");
    private static final BigDecimal OVEREXTENDED_ATR = new BigDecimal("2.50");

    public ShortTermVolatilityQuality evaluate(
            List<EastMoneyKLine> source,
            BigDecimal evaluationClose,
            LocalDate cutoffDate,
            ShortTermTechnicalSnapshot technical
    ) {
        List<EastMoneyKLine> rows = source == null ? List.of() : source.stream()
                .filter(row -> row != null && row.tradeDate() != null && row.close() != null)
                .filter(row -> cutoffDate == null || !row.tradeDate().isAfter(cutoffDate))
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        if (rows.size() < 25 || technical == null) {
            return ShortTermVolatilityQuality.unavailable("至少需要25根点时K线计算波动收缩与突破扩张");
        }
        if (evaluationClose == null || evaluationClose.signum() <= 0) {
            return ShortTermVolatilityQuality.unavailable("截止时点价格缺失，波动质量不参与排序");
        }
        BigDecimal atrPercent = technical.atr14Percent();
        if (atrPercent == null || atrPercent.signum() <= 0) {
            return ShortTermVolatilityQuality.unavailable("ATR缺失或为0，波动率归一化不参与排序");
        }

        EastMoneyKLine current = rows.get(rows.size() - 1);
        List<EastMoneyKLine> priorRows = rows.subList(0, rows.size() - 1);
        List<BigDecimal> priorTrueRanges = trueRanges(priorRows);
        List<String> gaps = new ArrayList<>();
        BigDecimal contractionRatio = contractionRatio(priorTrueRanges);
        if (contractionRatio == null) {
            gaps.add("收缩区间真实波幅样本不足");
        }
        boolean currentBarMatchesCutoff = cutoffDate != null && cutoffDate.equals(current.tradeDate());
        BigDecimal currentTrueRange = currentBarMatchesCutoff && priorRows.size() > 0
                ? trueRange(current, priorRows.get(priorRows.size() - 1))
                : null;
        BigDecimal priorAverage20 = average(last(priorTrueRanges, 20));
        BigDecimal expansionRatio = ratio(currentTrueRange, priorAverage20);
        if (!currentBarMatchesCutoff) {
            gaps.add("缺少截止日当日K线，突破扩张仅保留待确认状态");
        } else if (expansionRatio == null) {
            gaps.add("当日或历史真实波幅不足，无法确认突破扩张");
        }

        BigDecimal distanceAtr = ratio(technical.distanceToMa20Percent(), atrPercent);
        BigDecimal breakoutAtr = ratio(technical.breakoutFromPreviousHigh20Percent(), atrPercent);
        boolean contraction = contractionRatio != null
                && contractionRatio.compareTo(CONTRACTION_LIMIT) <= 0;
        boolean breakout = technical.breakoutFromPreviousHigh20Percent() != null
                && technical.breakoutFromPreviousHigh20Percent().compareTo(BigDecimal.ZERO) >= 0;
        boolean expansion = expansionRatio != null
                && expansionRatio.compareTo(EXPANSION_CONFIRMATION) >= 0;

        String state;
        String label;
        BigDecimal contribution;
        boolean contractionBreakout;
        if (distanceAtr != null && distanceAtr.abs().compareTo(OVEREXTENDED_ATR) > 0) {
            state = "OVEREXTENDED";
            label = "波动率过度拉开";
            contribution = new BigDecimal("-2.00");
            contractionBreakout = false;
        } else if (contraction && breakout && expansion) {
            state = "CONTRACTION_BREAKOUT";
            label = "收缩后扩张突破";
            contribution = new BigDecimal("3.00");
            contractionBreakout = true;
        } else if (contraction && breakout) {
            state = "CONTRACTION_READY";
            label = "波动收缩待扩张";
            contribution = new BigDecimal("1.50");
            contractionBreakout = false;
        } else if (breakout && expansion) {
            state = "EXPANSION_BREAKOUT";
            label = "扩张突破";
            contribution = new BigDecimal("1.00");
            contractionBreakout = false;
        } else {
            state = "NORMAL";
            label = "常态波动";
            contribution = BigDecimal.ZERO;
            contractionBreakout = false;
        }
        return new ShortTermVolatilityQuality(
                scale(atrPercent),
                scale(distanceAtr),
                scale(contractionRatio),
                scale(expansionRatio),
                scale(breakoutAtr),
                state,
                label,
                contractionBreakout,
                bounded(contribution, new BigDecimal("3")),
                gaps
        );
    }

    private BigDecimal contractionRatio(List<BigDecimal> trueRanges) {
        if (trueRanges == null || trueRanges.size() < 20) {
            return null;
        }
        List<BigDecimal> last20 = last(trueRanges, 20);
        BigDecimal recent5 = average(last(last20, 5));
        BigDecimal earlier15 = average(last20.subList(0, 15));
        return ratio(recent5, earlier15);
    }

    private List<BigDecimal> trueRanges(List<EastMoneyKLine> rows) {
        if (rows == null || rows.size() < 2) {
            return List.of();
        }
        List<BigDecimal> values = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            BigDecimal value = trueRange(rows.get(index), rows.get(index - 1));
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private BigDecimal trueRange(EastMoneyKLine current, EastMoneyKLine previous) {
        if (current == null || previous == null || current.high() == null
                || current.low() == null || previous.close() == null) {
            return null;
        }
        return current.high().subtract(current.low()).abs()
                .max(current.high().subtract(previous.close()).abs())
                .max(current.low().subtract(previous.close()).abs());
    }

    private List<BigDecimal> last(List<BigDecimal> values, int count) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.subList(Math.max(0, values.size() - count), values.size());
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> valid = values == null ? List.of() : values.stream()
                .filter(value -> value != null)
                .toList();
        if (valid.isEmpty()) {
            return null;
        }
        return valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(valid.size()), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal bounded(BigDecimal value, BigDecimal absoluteLimit) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.max(absoluteLimit.negate()).min(absoluteLimit).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}

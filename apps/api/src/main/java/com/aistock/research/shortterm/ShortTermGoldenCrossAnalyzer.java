package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Component
class ShortTermGoldenCrossAnalyzer {

    private static final BigDecimal APPROACHING_MIN_SPREAD = new BigDecimal("-0.80");

    ShortTermGoldenCrossSnapshot analyze(List<EastMoneyKLine> source, boolean latestBarCompleted) {
        List<EastMoneyKLine> rows = sortedRows(source);
        if (rows.size() < 20) {
            return ShortTermGoldenCrossSnapshot.unavailable();
        }

        int latestIndex = rows.size() - 1;
        BigDecimal latestSpread = spreadAt(rows, latestIndex);
        BigDecimal latestMa5 = movingAverageAt(rows, latestIndex, 5);
        BigDecimal latestMa10 = movingAverageAt(rows, latestIndex, 10);
        BigDecimal latestMa20 = movingAverageAt(rows, latestIndex, 20);
        if (latestSpread == null || latestMa20 == null) {
            return ShortTermGoldenCrossSnapshot.unavailable();
        }

        int latestCrossIndex = latestCrossIndex(rows, latestIndex);
        if (!latestBarCompleted && latestCrossIndex == latestIndex) {
            return snapshot(
                    rows.get(latestCrossIndex).tradeDate(),
                    0,
                    latestSpread,
                    spreadTrend(rows, latestIndex),
                    alignment(latestMa5, latestMa10, latestMa20, false),
                    "FORMING",
                    2,
                    "PARTIAL"
            );
        }

        int completedIndex = latestBarCompleted ? latestIndex : latestIndex - 1;
        if (completedIndex < 19) {
            return ShortTermGoldenCrossSnapshot.unavailable();
        }
        int completedCrossIndex = latestCrossIndex(rows, completedIndex);
        int daysSinceCross = completedCrossIndex < 0 ? -1 : completedIndex - completedCrossIndex;
        boolean approaching = isApproaching(rows, completedIndex);

        if (completedCrossIndex >= 0 && daysSinceCross <= 3) {
            return snapshot(
                    rows.get(completedCrossIndex).tradeDate(),
                    daysSinceCross,
                    latestSpread,
                    spreadTrend(rows, completedIndex),
                    alignment(latestMa5, latestMa10, latestMa20, false),
                    "CONFIRMED",
                    3,
                    "COMPLETE"
            );
        }
        if (completedCrossIndex >= 0) {
            return snapshot(
                    rows.get(completedCrossIndex).tradeDate(),
                    daysSinceCross,
                    latestSpread,
                    spreadTrend(rows, completedIndex),
                    alignment(latestMa5, latestMa10, latestMa20, false),
                    "ESTABLISHED",
                    1,
                    "COMPLETE"
            );
        }
        if (approaching) {
            return snapshot(
                    null,
                    null,
                    latestSpread,
                    "NARROWING",
                    alignment(latestMa5, latestMa10, latestMa20, true),
                    "APPROACHING",
                    2,
                    "COMPLETE"
            );
        }
        return snapshot(
                null,
                null,
                latestSpread,
                spreadTrend(rows, completedIndex),
                alignment(latestMa5, latestMa10, latestMa20, false),
                "NONE",
                0,
                "COMPLETE"
        );
    }

    private List<EastMoneyKLine> sortedRows(List<EastMoneyKLine> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream()
                .filter(row -> row != null && row.tradeDate() != null && row.close() != null)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
    }

    private int latestCrossIndex(List<EastMoneyKLine> rows, int throughIndex) {
        for (int index = throughIndex; index >= 10; index--) {
            if (crossedAt(rows, index)) {
                return index;
            }
        }
        return -1;
    }

    private boolean crossedAt(List<EastMoneyKLine> rows, int index) {
        if (index < 10) return false;
        BigDecimal currentMa5 = movingAverageAt(rows, index, 5);
        BigDecimal currentMa10 = movingAverageAt(rows, index, 10);
        BigDecimal previousMa5 = movingAverageAt(rows, index - 1, 5);
        BigDecimal previousMa10 = movingAverageAt(rows, index - 1, 10);
        return currentMa5 != null && currentMa10 != null
                && previousMa5 != null && previousMa10 != null
                && currentMa5.compareTo(currentMa10) > 0
                && previousMa5.compareTo(previousMa10) <= 0;
    }

    private BigDecimal spreadAt(List<EastMoneyKLine> rows, int index) {
        BigDecimal ma5 = movingAverageAt(rows, index, 5);
        BigDecimal ma10 = movingAverageAt(rows, index, 10);
        if (ma5 == null || ma10 == null || ma10.signum() == 0) return null;
        return ma5.subtract(ma10).multiply(new BigDecimal("100"))
                .divide(ma10, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal movingAverageAt(List<EastMoneyKLine> rows, int index, int window) {
        if (index < window - 1 || index >= rows.size()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int position = index - window + 1; position <= index; position++) {
            BigDecimal close = rows.get(position).close();
            if (close == null) {
                return null;
            }
            total = total.add(close);
        }
        return total.divide(BigDecimal.valueOf(window), 8, RoundingMode.HALF_UP);
    }

    private boolean isApproaching(List<EastMoneyKLine> rows, int latestIndex) {
        BigDecimal latestSpread = spreadAt(rows, latestIndex);
        BigDecimal latestMa5 = movingAverageAt(rows, latestIndex, 5);
        BigDecimal ma5ThreeBarsAgo = movingAverageAt(rows, latestIndex - 3, 5);
        if (latestSpread == null || latestMa5 == null || ma5ThreeBarsAgo == null
                || latestSpread.compareTo(APPROACHING_MIN_SPREAD) < 0
                || latestSpread.compareTo(BigDecimal.ZERO) > 0
                || latestMa5.compareTo(ma5ThreeBarsAgo) <= 0) {
            return false;
        }
        return "NARROWING".equals(spreadTrend(rows, latestIndex));
    }

    private String spreadTrend(List<EastMoneyKLine> rows, int latestIndex) {
        BigDecimal oldest = spreadAt(rows, latestIndex - 2);
        BigDecimal middle = spreadAt(rows, latestIndex - 1);
        BigDecimal latest = spreadAt(rows, latestIndex);
        if (oldest == null || middle == null || latest == null) {
            return "UNAVAILABLE";
        }
        BigDecimal oldestAbsolute = oldest.abs();
        BigDecimal middleAbsolute = middle.abs();
        BigDecimal latestAbsolute = latest.abs();
        if (oldestAbsolute.compareTo(middleAbsolute) > 0 && middleAbsolute.compareTo(latestAbsolute) > 0) {
            return "NARROWING";
        }
        if (oldestAbsolute.compareTo(middleAbsolute) < 0 && middleAbsolute.compareTo(latestAbsolute) < 0) {
            return "WIDENING";
        }
        return "FLAT";
    }

    private String alignment(BigDecimal ma5, BigDecimal ma10, BigDecimal ma20, boolean approaching) {
        if (ma5 == null || ma10 == null || ma20 == null) {
            return "UNAVAILABLE";
        }
        if (ma5.compareTo(ma10) > 0 && ma10.compareTo(ma20) > 0) {
            return "BULLISH_STACK";
        }
        if (ma5.compareTo(ma10) > 0) {
            return "MA5_ABOVE_MA10";
        }
        return approaching ? "CONVERGING" : "BEARISH";
    }

    private ShortTermGoldenCrossSnapshot snapshot(
            LocalDate crossDate,
            Integer tradingDaysSinceCross,
            BigDecimal spread,
            String spreadTrend,
            String alignment,
            String state,
            int priorityTier,
            String evidenceStatus
    ) {
        return new ShortTermGoldenCrossSnapshot(
                ShortTermGoldenCrossSnapshot.RULE_VERSION,
                state,
                stateLabel(state),
                crossDate,
                tradingDaysSinceCross,
                spread,
                spreadTrend,
                alignment,
                priorityTier,
                evidenceStatus
        );
    }

    private String stateLabel(String state) {
        return switch (state) {
            case "CONFIRMED" -> "金叉已确认";
            case "APPROACHING" -> "临界交汇";
            case "FORMING" -> "金叉形成中";
            case "ESTABLISHED" -> "多头延续";
            case "NONE" -> "尚未交汇";
            default -> "金叉数据不足";
        };
    }
}

package com.aistock.research.leader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LeaderRuleSet {

    static final String RULE_VERSION = "potential-leader-v1";
    static final BigDecimal ZERO = BigDecimal.ZERO;
    static final BigDecimal ONE = BigDecimal.ONE;
    static final BigDecimal HUNDRED = new BigDecimal("100");
    static final BigDecimal MIN_DIRECTION_SCORE = new BigDecimal("60");
    static final BigDecimal MIN_CANDIDATE_SCORE = new BigDecimal("55");
    static final BigDecimal MIN_TURNOVER = new BigDecimal("0.30");
    static final BigDecimal MIN_AMOUNT_PERCENTILE = new BigDecimal("0.20");
    static final BigDecimal MIN_SEAT_ADJUSTMENT = new BigDecimal("-5");
    static final BigDecimal MAX_SEAT_ADJUSTMENT = new BigDecimal("10");
    static final BigDecimal MAX_RISK = new BigDecimal("30");

    private LeaderRuleSet() {
    }

    static BigDecimal percentile(BigDecimal value, List<BigDecimal> values) {
        if (value == null || values == null || values.isEmpty()) {
            return ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        List<BigDecimal> sorted = new ArrayList<>(values.stream().filter(v -> v != null).toList());
        if (sorted.isEmpty()) {
            return ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        sorted.sort(Comparator.naturalOrder());
        long inclusiveRank = sorted.stream().filter(candidate -> candidate.compareTo(value) <= 0).count();
        return BigDecimal.valueOf(inclusiveRank)
                .divide(BigDecimal.valueOf(sorted.size()), 6, RoundingMode.HALF_UP);
    }

    static BigDecimal linear(BigDecimal value, BigDecimal low, BigDecimal high, BigDecimal maxPoints) {
        if (value == null || low == null || high == null || maxPoints == null
                || high.compareTo(low) <= 0 || value.compareTo(low) <= 0) {
            return scale(ZERO);
        }
        if (value.compareTo(high) >= 0) {
            return scale(maxPoints);
        }
        return scale(value.subtract(low).multiply(maxPoints)
                .divide(high.subtract(low), 8, RoundingMode.HALF_UP));
    }

    static BigDecimal clamp(BigDecimal value, BigDecimal low, BigDecimal high) {
        BigDecimal normalized = value == null ? ZERO : value;
        if (normalized.compareTo(low) < 0) {
            return low;
        }
        if (normalized.compareTo(high) > 0) {
            return high;
        }
        return normalized;
    }

    static BigDecimal scale(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}

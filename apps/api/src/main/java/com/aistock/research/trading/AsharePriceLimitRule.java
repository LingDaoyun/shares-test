package com.aistock.research.trading;

import java.math.BigDecimal;

public final class AsharePriceLimitRule {

    private static final BigDecimal MAIN_BOARD_LIMIT_PERCENT = new BigDecimal("10");
    private static final BigDecimal GROWTH_BOARD_LIMIT_PERCENT = new BigDecimal("20");
    private static final BigDecimal BSE_LIMIT_PERCENT = new BigDecimal("30");
    private static final BigDecimal LIMIT_LIKE_RATIO = new BigDecimal("0.95");

    private AsharePriceLimitRule() {
    }

    public static boolean isLimitUpLike(String symbol, BigDecimal changePercent) {
        return changePercent != null
                && changePercent.compareTo(limitPercent(symbol).multiply(LIMIT_LIKE_RATIO)) >= 0;
    }

    public static boolean isLimitDownLike(String symbol, BigDecimal changePercent) {
        return changePercent != null
                && changePercent.compareTo(limitPercent(symbol).multiply(LIMIT_LIKE_RATIO).negate()) <= 0;
    }

    public static BigDecimal limitPercent(String symbol) {
        if (symbol == null) {
            return MAIN_BOARD_LIMIT_PERCENT;
        }
        if (symbol.startsWith("300") || symbol.startsWith("301") || symbol.startsWith("688")) {
            return GROWTH_BOARD_LIMIT_PERCENT;
        }
        if (symbol.startsWith("4") || symbol.startsWith("8") || symbol.startsWith("92")) {
            return BSE_LIMIT_PERCENT;
        }
        return MAIN_BOARD_LIMIT_PERCENT;
    }
}

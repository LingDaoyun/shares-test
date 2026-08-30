package com.aistock.research.firstboard;

import java.util.Map;

final class FirstBoardCurvePolicy {

    static final int MINIMUM_VALID_BARS = 15;

    private FirstBoardCurvePolicy() {
    }

    static boolean hasValidCurve(String symbol, Map<String, Integer> counts) {
        return symbol != null
                && counts != null
                && counts.getOrDefault(symbol, 0) >= MINIMUM_VALID_BARS;
    }
}

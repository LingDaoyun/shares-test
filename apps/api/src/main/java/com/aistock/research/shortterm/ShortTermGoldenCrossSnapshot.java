package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ShortTermGoldenCrossSnapshot(
        String ruleVersion,
        String state,
        String stateLabel,
        LocalDate crossDate,
        Integer tradingDaysSinceCross,
        BigDecimal ma5Ma10SpreadPercent,
        String spreadTrend,
        String maAlignment,
        int priorityTier,
        String evidenceStatus
) {
    public static final String RULE_VERSION = "short-golden-cross-v1.0.0";

    public static ShortTermGoldenCrossSnapshot unavailable() {
        return new ShortTermGoldenCrossSnapshot(
                RULE_VERSION, "UNAVAILABLE", "金叉数据不足", null, null, null,
                "UNAVAILABLE", "UNAVAILABLE", 0, "UNAVAILABLE");
    }

    public boolean confirmedRecent() {
        return "CONFIRMED".equals(state)
                && tradingDaysSinceCross != null
                && tradingDaysSinceCross >= 0
                && tradingDaysSinceCross <= 3;
    }

    public boolean watchLayer() {
        return "APPROACHING".equals(state) || "FORMING".equals(state);
    }
}

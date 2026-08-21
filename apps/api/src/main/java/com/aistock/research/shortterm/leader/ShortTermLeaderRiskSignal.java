package com.aistock.research.shortterm.leader;

import java.math.BigDecimal;
import java.util.Objects;

public record ShortTermLeaderRiskSignal(
        Track track,
        String symbol,
        String name,
        String direction,
        BigDecimal currentChangePercent,
        BigDecimal baselineChangePercent,
        BigDecimal changeDeltaPercentPoints,
        Integer currentAmountRank,
        Integer baselineAmountRank,
        BigDecimal amountSharePercent,
        BigDecimal totalMarketValue,
        String reason
) {

    public ShortTermLeaderRiskSignal {
        track = Objects.requireNonNull(track, "track");
        symbol = text(symbol);
        name = text(name);
        direction = text(direction);
        currentChangePercent = currentChangePercent == null ? BigDecimal.ZERO : currentChangePercent;
        amountSharePercent = amountSharePercent == null ? BigDecimal.ZERO : amountSharePercent;
        reason = text(reason);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Track { WEIGHT, THEME }
}

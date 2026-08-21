package com.aistock.research.shortterm.leader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermLeaderSnapshot(
        String ruleVersion,
        String snapshotId,
        LocalDate tradeDate,
        Instant capturedAt,
        int quoteCount,
        List<WeightObservation> weights,
        List<DirectionObservation> directions
) {

    public ShortTermLeaderSnapshot {
        weights = limitedCopy(weights, 50);
        directions = limitedCopy(directions, 8);
    }

    private static <T> List<T> limitedCopy(List<T> values, int maximum) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copy = List.copyOf(values);
        return copy.size() <= maximum ? copy : List.copyOf(copy.subList(0, maximum));
    }

    public record WeightObservation(
            String symbol,
            String name,
            String industry,
            BigDecimal changePercent,
            Integer amountRank,
            BigDecimal amountSharePercent,
            BigDecimal totalMarketValue
    ) {
    }

    public record DirectionObservation(
            String code,
            String label,
            BigDecimal heatScore,
            int rank,
            List<LeaderObservation> leaders
    ) {

        public DirectionObservation {
            leaders = limitedCopy(leaders, 3);
        }
    }

    public record LeaderObservation(
            String symbol,
            String name,
            String industry,
            BigDecimal changePercent,
            Integer amountRank,
            BigDecimal amountSharePercent,
            BigDecimal totalMarketValue
    ) {
    }
}

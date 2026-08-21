package com.aistock.research.shortterm.leader;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface ShortTermLeaderSnapshotStore {

    Optional<ShortTermLeaderSnapshot> latestSameDayBefore(
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt
    );

    Optional<ShortTermLeaderSnapshot> latestBeforeTradeDate(
            String ruleVersion,
            LocalDate tradeDate
    );

    default List<ShortTermLeaderCheckpoint> sameDayCheckpointsBefore(
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt
    ) {
        return List.of();
    }

    void save(ShortTermLeaderSnapshot snapshot);

    default void saveCheckpoint(
            ShortTermLeaderSnapshot snapshot,
            ShortTermLeaderRisk risk
    ) {
        save(snapshot);
    }
}

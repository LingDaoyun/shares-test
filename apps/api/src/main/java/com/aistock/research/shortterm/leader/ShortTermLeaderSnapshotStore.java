package com.aistock.research.shortterm.leader;

import java.time.Instant;
import java.time.LocalDate;
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

    void save(ShortTermLeaderSnapshot snapshot);
}

package com.aistock.research.shortterm.leader;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShortTermLeaderSnapshotRepository
        extends JpaRepository<ShortTermLeaderSnapshotEntity, String> {

    Optional<ShortTermLeaderSnapshotEntity>
    findFirstByRuleVersionAndTradeDateAndCapturedAtLessThanOrderByCapturedAtDescSnapshotIdDesc(
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt
    );

    Optional<ShortTermLeaderSnapshotEntity>
    findFirstByRuleVersionAndTradeDateLessThanOrderByTradeDateDescCapturedAtDescSnapshotIdDesc(
            String ruleVersion,
            LocalDate tradeDate
    );

    List<ShortTermLeaderSnapshotEntity>
    findByRuleVersionAndTradeDateAndCapturedAtLessThanOrderByCapturedAtAscSnapshotIdAsc(
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt
    );
}

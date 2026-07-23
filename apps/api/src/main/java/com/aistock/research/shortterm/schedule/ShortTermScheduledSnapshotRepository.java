package com.aistock.research.shortterm.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public interface ShortTermScheduledSnapshotRepository
        extends JpaRepository<ShortTermScheduledSnapshotEntity, String> {

    Optional<ShortTermScheduledSnapshotEntity>
    findFirstByTradeDateOrderByUpdatedAtDescSnapshotKeyDesc(LocalDate tradeDate);

    Optional<ShortTermScheduledSnapshotEntity>
    findByTradeDateAndStageAndParameterFingerprint(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ShortTermScheduledSnapshotEntity snapshot
            set snapshot.status = :runningStatus,
                snapshot.attemptCount = snapshot.attemptCount + 1
            where snapshot.snapshotKey = :snapshotKey
              and snapshot.status = :failedStatus
            """)
    int reclaimFailed(
            @Param("snapshotKey") String snapshotKey,
            @Param("runningStatus") ShortTermSnapshotStatus runningStatus,
            @Param("failedStatus") ShortTermSnapshotStatus failedStatus
    );
}

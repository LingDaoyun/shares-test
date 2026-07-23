package com.aistock.research.shortterm.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShortTermScheduledSnapshotRepository
        extends JpaRepository<ShortTermScheduledSnapshotEntity, String> {

    Optional<ShortTermScheduledSnapshotEntity>
    findFirstByTradeDateOrderByUpdatedAtDescSnapshotKeyDesc(LocalDate tradeDate);

    Optional<ShortTermScheduledSnapshotEntity>
    findFirstByTradeDateAndStageOrderByUpdatedAtDescSnapshotKeyDesc(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage
    );

    List<ShortTermScheduledSnapshotEntity>
    findAllByTradeDateAndStageAndStatusOrderByStartedAtAscSnapshotKeyAsc(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            ShortTermSnapshotStatus status
    );

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
                snapshot.attemptCount = snapshot.attemptCount + 1,
                snapshot.startedAt = :startedAt,
                snapshot.completedAt = null,
                snapshot.reportJson = null,
                snapshot.dataCutoffAt = null,
                snapshot.message = :runningMessage,
                snapshot.blockedReasonsJson = null,
                snapshot.updatedAt = :startedAt
            where snapshot.snapshotKey = :snapshotKey
              and snapshot.status = :failedStatus
            """)
    int reclaimFailed(
            @Param("snapshotKey") String snapshotKey,
            @Param("runningStatus") ShortTermSnapshotStatus runningStatus,
            @Param("failedStatus") ShortTermSnapshotStatus failedStatus,
            @Param("startedAt") java.time.Instant startedAt,
            @Param("runningMessage") String runningMessage
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ShortTermScheduledSnapshotEntity snapshot
            set snapshot.attemptCount = snapshot.attemptCount + 1,
                snapshot.startedAt = :restartedAt,
                snapshot.completedAt = null,
                snapshot.reportJson = null,
                snapshot.dataCutoffAt = null,
                snapshot.message = :runningMessage,
                snapshot.blockedReasonsJson = null,
                snapshot.updatedAt = :restartedAt
            where snapshot.snapshotKey = :snapshotKey
              and snapshot.status = :runningStatus
              and snapshot.attemptCount = :expectedAttemptCount
              and snapshot.startedAt <= :staleCutoff
              and snapshot.updatedAt <= :staleCutoff
            """)
    int reclaimStaleRunning(
            @Param("snapshotKey") String snapshotKey,
            @Param("expectedAttemptCount") int expectedAttemptCount,
            @Param("runningStatus") ShortTermSnapshotStatus runningStatus,
            @Param("staleCutoff") java.time.Instant staleCutoff,
            @Param("restartedAt") java.time.Instant restartedAt,
            @Param("runningMessage") String runningMessage
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ShortTermScheduledSnapshotEntity snapshot
            set snapshot.status = :terminalStatus,
                snapshot.reportJson = :reportJson,
                snapshot.dataCutoffAt = :dataCutoffAt,
                snapshot.completedAt = :completedAt,
                snapshot.message = :message,
                snapshot.blockedReasonsJson = :blockedReasonsJson,
                snapshot.updatedAt = :completedAt
            where snapshot.snapshotKey = :snapshotKey
              and snapshot.status = :runningStatus
              and snapshot.attemptCount = :attemptCount
            """)
    int publishTerminal(
            @Param("snapshotKey") String snapshotKey,
            @Param("attemptCount") int attemptCount,
            @Param("runningStatus") ShortTermSnapshotStatus runningStatus,
            @Param("terminalStatus") ShortTermSnapshotStatus terminalStatus,
            @Param("reportJson") String reportJson,
            @Param("dataCutoffAt") java.time.Instant dataCutoffAt,
            @Param("completedAt") java.time.Instant completedAt,
            @Param("message") String message,
            @Param("blockedReasonsJson") String blockedReasonsJson
    );
}

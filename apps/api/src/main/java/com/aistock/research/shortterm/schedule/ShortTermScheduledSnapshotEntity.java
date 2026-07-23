package com.aistock.research.shortterm.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "short_term_scheduled_snapshot")
public class ShortTermScheduledSnapshotEntity implements Persistable<String> {

    @Id
    @Column(name = "snapshot_key", nullable = false, length = 160)
    private String snapshotKey;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 32)
    private ShortTermSnapshotStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ShortTermSnapshotStatus status;

    @Column(name = "parameter_fingerprint", nullable = false, length = 64)
    private String parameterFingerprint;

    @Column(name = "parameters_json", nullable = false, columnDefinition = "TEXT")
    private String parametersJson;

    @Column(name = "report_json", columnDefinition = "TEXT")
    private String reportJson;

    @Column(name = "data_cutoff_at")
    private Instant dataCutoffAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "blocked_reason", length = 2000)
    private String blockedReasonsJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    protected ShortTermScheduledSnapshotEntity() {
    }

    public ShortTermScheduledSnapshotEntity(
            String snapshotKey,
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            String parametersJson,
            Instant startedAt
    ) {
        this.snapshotKey = snapshotKey;
        this.tradeDate = tradeDate;
        this.stage = stage;
        this.status = ShortTermSnapshotStatus.RUNNING;
        this.parameterFingerprint = parameterFingerprint;
        this.parametersJson = parametersJson;
        this.startedAt = startedAt;
        this.attemptCount = 1;
        this.message = "正在执行";
        this.updatedAt = startedAt;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newEntity = false;
    }

    @Override
    public String getId() { return snapshotKey; }
    @Override
    public boolean isNew() { return newEntity; }
    public String getSnapshotKey() { return snapshotKey; }
    public LocalDate getTradeDate() { return tradeDate; }
    public ShortTermSnapshotStage getStage() { return stage; }
    public ShortTermSnapshotStatus getStatus() { return status; }
    public String getParameterFingerprint() { return parameterFingerprint; }
    public String getParametersJson() { return parametersJson; }
    public String getReportJson() { return reportJson; }
    public Instant getDataCutoffAt() { return dataCutoffAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getAttemptCount() { return attemptCount; }
    public String getMessage() { return message; }
    public String getBlockedReasonsJson() { return blockedReasonsJson; }
    public Instant getUpdatedAt() { return updatedAt; }
}

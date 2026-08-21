package com.aistock.research.shortterm.leader;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "short_term_leader_snapshot")
public class ShortTermLeaderSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "rule_version", nullable = false, length = 80)
    private String ruleVersion;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "risk_json", columnDefinition = "TEXT")
    private String riskJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ShortTermLeaderSnapshotEntity() {
    }

    public ShortTermLeaderSnapshotEntity(
            String snapshotId,
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt,
            String snapshotJson,
            String riskJson,
            Instant createdAt
    ) {
        this.snapshotId = snapshotId;
        this.ruleVersion = ruleVersion;
        this.tradeDate = tradeDate;
        this.capturedAt = capturedAt;
        this.snapshotJson = snapshotJson;
        this.riskJson = riskJson;
        this.createdAt = createdAt;
    }

    public ShortTermLeaderSnapshotEntity(
            String snapshotId,
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt,
            String snapshotJson,
            Instant createdAt
    ) {
        this(snapshotId, ruleVersion, tradeDate, capturedAt, snapshotJson, null, createdAt);
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public String getRiskJson() {
        return riskJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

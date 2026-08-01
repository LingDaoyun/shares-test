package com.aistock.research.shortterm.chip;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "short_term_chip_verification")
public class ShortTermChipVerificationEntity implements Persistable<String> {

    @Id
    @Column(name = "verification_key", nullable = false, length = 160)
    private String verificationKey;

    @Column(name = "symbol", nullable = false, length = 12)
    private String symbol;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 24)
    private ChipVerificationStatus status;

    @Column(name = "verification_coefficient", nullable = false, precision = 8, scale = 4)
    private BigDecimal verificationCoefficient;

    @Column(name = "average_cost_deviation", precision = 12, scale = 6)
    private BigDecimal averageCostDeviation;

    @Column(name = "cost_band_overlap", precision = 12, scale = 6)
    private BigDecimal costBandOverlap;

    @Column(name = "winner_rate_deviation", precision = 12, scale = 6)
    private BigDecimal winnerRateDeviation;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "external_summary_json", columnDefinition = "TEXT")
    private String externalSummaryJson;

    @Column(name = "data_cutoff_at")
    private Instant dataCutoffAt;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Transient
    private boolean newEntity = true;

    protected ShortTermChipVerificationEntity() {
    }

    public ShortTermChipVerificationEntity(
            String verificationKey,
            String symbol,
            LocalDate tradeDate,
            String modelVersion,
            ShortTermChipSnapshot snapshot,
            String snapshotJson,
            String externalSummaryJson,
            Instant dataCutoffAt,
            Instant observedAt,
            String errorSummary
    ) {
        this.verificationKey = verificationKey;
        this.symbol = symbol;
        this.tradeDate = tradeDate;
        this.modelVersion = modelVersion;
        this.status = snapshot.verificationStatus();
        this.verificationCoefficient = snapshot.verificationCoefficient();
        this.averageCostDeviation = snapshot.averageCostDeviation();
        this.costBandOverlap = snapshot.cost70BandOverlap();
        this.winnerRateDeviation = snapshot.winnerRateDeviation();
        this.snapshotJson = snapshotJson;
        this.externalSummaryJson = externalSummaryJson;
        this.dataCutoffAt = dataCutoffAt;
        this.observedAt = observedAt;
        this.errorSummary = errorSummary;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newEntity = false;
    }

    void markExisting() {
        newEntity = false;
    }

    @Override
    public String getId() {
        return verificationKey;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getVerificationKey() { return verificationKey; }
    public String getSymbol() { return symbol; }
    public LocalDate getTradeDate() { return tradeDate; }
    public String getModelVersion() { return modelVersion; }
    public ChipVerificationStatus getStatus() { return status; }
    public String getSnapshotJson() { return snapshotJson; }
    public String getExternalSummaryJson() { return externalSummaryJson; }
    public Instant getDataCutoffAt() { return dataCutoffAt; }
    public Instant getObservedAt() { return observedAt; }
    public String getErrorSummary() { return errorSummary; }
}

package com.aistock.research.shortterm.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

@Entity
@Table(name = "short_term_signal_outcome")
public class ShortTermSignalOutcomeEntity implements Persistable<String> {

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "MATURED",
            "UNAVAILABLE_SUSPENDED_OR_MISSING",
            "UNAVAILABLE_BASELINE",
            "UNAVAILABLE_TARGET_DATE"
    );

    @Id
    @Column(name = "outcome_id", nullable = false, length = 64)
    private String outcomeId;

    @Column(name = "observation_id", nullable = false, length = 64)
    private String observationId;

    @Column(name = "horizon", nullable = false, length = 8)
    private String horizon;

    @Column(name = "target_trade_date", nullable = false)
    private LocalDate targetTradeDate;

    @Column(name = "evaluation_price", precision = 20, scale = 6)
    private BigDecimal evaluationPrice;

    @Column(name = "gross_return_percent", precision = 14, scale = 6)
    private BigDecimal grossReturnPercent;

    @Column(name = "net_return_percent", precision = 14, scale = 6)
    private BigDecimal netReturnPercent;

    @Column(name = "max_favorable_excursion_percent", precision = 14, scale = 6)
    private BigDecimal maxFavorableExcursionPercent;

    @Column(name = "max_adverse_excursion_percent", precision = 14, scale = 6)
    private BigDecimal maxAdverseExcursionPercent;

    @Column(name = "status", nullable = false, length = 64)
    private String status;

    @Column(name = "source_name", length = 128)
    private String sourceName;

    @Column(name = "market_timestamp")
    private Instant marketTimestamp;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "calculated_at")
    private Instant calculatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity = true;

    protected ShortTermSignalOutcomeEntity() {
    }

    private ShortTermSignalOutcomeEntity(
            String outcomeId,
            String observationId,
            String horizon,
            LocalDate targetTradeDate,
            Instant createdAt
    ) {
        this.outcomeId = outcomeId;
        this.observationId = observationId;
        this.horizon = horizon;
        this.targetTradeDate = targetTradeDate;
        this.status = "PENDING";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public static ShortTermSignalOutcomeEntity pending(
            String outcomeId,
            String observationId,
            String horizon,
            LocalDate targetTradeDate,
            Instant createdAt
    ) {
        return new ShortTermSignalOutcomeEntity(
                outcomeId, observationId, horizon, targetTradeDate, createdAt);
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newEntity = false;
    }

    public void applyEvaluation(
            ShortTermHorizonEvaluation evaluation,
            String sourceName,
            Instant marketTimestamp,
            Instant calculatedAt
    ) {
        requireMutable();
        this.evaluationPrice = evaluation.evaluationPrice();
        this.grossReturnPercent = evaluation.grossReturnPercent();
        this.netReturnPercent = evaluation.netReturnPercent();
        this.maxFavorableExcursionPercent = evaluation.maxFavorableExcursionPercent();
        this.maxAdverseExcursionPercent = evaluation.maxAdverseExcursionPercent();
        this.status = evaluation.status();
        this.sourceName = sourceName;
        this.marketTimestamp = marketTimestamp;
        this.detail = evaluation.detail();
        this.calculatedAt = calculatedAt;
        this.updatedAt = calculatedAt;
    }

    public void markSourceUnavailable(String sourceName, String detail, Instant attemptedAt) {
        requireMutable();
        this.status = "SOURCE_UNAVAILABLE_RETRYABLE";
        this.sourceName = sourceName;
        this.detail = detail;
        this.calculatedAt = attemptedAt;
        this.updatedAt = attemptedAt;
    }

    public boolean terminal() {
        return TERMINAL_STATUSES.contains(status);
    }

    private void requireMutable() {
        if (terminal()) {
            throw new IllegalStateException("已成熟或已确认不可用的短线结果不可覆盖");
        }
    }

    @Override
    public String getId() { return outcomeId; }
    @Override
    public boolean isNew() { return newEntity; }
    public String getOutcomeId() { return outcomeId; }
    public String getObservationId() { return observationId; }
    public String getHorizon() { return horizon; }
    public LocalDate getTargetTradeDate() { return targetTradeDate; }
    public BigDecimal getEvaluationPrice() { return evaluationPrice; }
    public BigDecimal getGrossReturnPercent() { return grossReturnPercent; }
    public BigDecimal getNetReturnPercent() { return netReturnPercent; }
    public BigDecimal getMaxFavorableExcursionPercent() { return maxFavorableExcursionPercent; }
    public BigDecimal getMaxAdverseExcursionPercent() { return maxAdverseExcursionPercent; }
    public String getStatus() { return status; }
    public String getSourceName() { return sourceName; }
    public Instant getMarketTimestamp() { return marketTimestamp; }
    public String getDetail() { return detail; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

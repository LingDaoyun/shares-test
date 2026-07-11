package com.aistock.research.tradefeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "strategy_outcome_snapshot")
public class TradeOutcomeEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 36)
    private String snapshotId;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    @Column(name = "baseline_type", nullable = false, length = 32)
    private String baselineType;

    @Column(name = "horizon", nullable = false, length = 16)
    private String horizon;

    @Column(name = "baseline_price", precision = 20, scale = 6)
    private BigDecimal baselinePrice;

    @Column(name = "evaluation_price", precision = 20, scale = 6)
    private BigDecimal evaluationPrice;

    @Column(name = "evaluation_date")
    private LocalDate evaluationDate;

    @Column(name = "return_pct", precision = 12, scale = 4)
    private BigDecimal returnPct;

    @Column(name = "max_runup_pct", precision = 12, scale = 4)
    private BigDecimal maxRunupPct;

    @Column(name = "max_drawdown_pct", precision = 12, scale = 4)
    private BigDecimal maxDrawdownPct;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "source_name", length = 128)
    private String sourceName;

    @Column(name = "market_timestamp")
    private Instant marketTimestamp;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    protected TradeOutcomeEntity() {
    }

    private TradeOutcomeEntity(
            String snapshotId,
            String caseId,
            String baselineType,
            String horizon,
            BigDecimal baselinePrice,
            BigDecimal evaluationPrice,
            LocalDate evaluationDate,
            BigDecimal returnPct,
            BigDecimal maxRunupPct,
            BigDecimal maxDrawdownPct,
            String status,
            String sourceName,
            Instant marketTimestamp,
            Instant calculatedAt
    ) {
        this.snapshotId = snapshotId;
        this.caseId = caseId;
        this.baselineType = baselineType;
        this.horizon = horizon;
        this.baselinePrice = baselinePrice;
        this.evaluationPrice = evaluationPrice;
        this.evaluationDate = evaluationDate;
        this.returnPct = returnPct;
        this.maxRunupPct = maxRunupPct;
        this.maxDrawdownPct = maxDrawdownPct;
        this.status = status;
        this.sourceName = sourceName;
        this.marketTimestamp = marketTimestamp;
        this.calculatedAt = calculatedAt;
    }

    public static TradeOutcomeEntity pending(
            String snapshotId,
            String caseId,
            String baselineType,
            String horizon,
            Instant calculatedAt
    ) {
        return pending(snapshotId, caseId, baselineType, horizon, null, null, calculatedAt);
    }

    public static TradeOutcomeEntity pending(
            String snapshotId,
            String caseId,
            String baselineType,
            String horizon,
            String sourceName,
            Instant marketTimestamp,
            Instant calculatedAt
    ) {
        return new TradeOutcomeEntity(
                snapshotId,
                caseId,
                baselineType,
                horizon,
                null,
                null,
                null,
                null,
                null,
                null,
                "PENDING",
                sourceName,
                marketTimestamp,
                calculatedAt
        );
    }

    public static TradeOutcomeEntity matured(
            String snapshotId,
            String caseId,
            String baselineType,
            String horizon,
            BigDecimal baselinePrice,
            BigDecimal evaluationPrice,
            LocalDate evaluationDate,
            BigDecimal returnPct,
            BigDecimal maxRunupPct,
            BigDecimal maxDrawdownPct,
            Instant calculatedAt
    ) {
        return matured(
                snapshotId, caseId, baselineType, horizon, baselinePrice, evaluationPrice,
                evaluationDate, returnPct, maxRunupPct, maxDrawdownPct, null, null, calculatedAt);
    }

    public static TradeOutcomeEntity matured(
            String snapshotId,
            String caseId,
            String baselineType,
            String horizon,
            BigDecimal baselinePrice,
            BigDecimal evaluationPrice,
            LocalDate evaluationDate,
            BigDecimal returnPct,
            BigDecimal maxRunupPct,
            BigDecimal maxDrawdownPct,
            String sourceName,
            Instant marketTimestamp,
            Instant calculatedAt
    ) {
        return new TradeOutcomeEntity(
                snapshotId,
                caseId,
                baselineType,
                horizon,
                baselinePrice,
                evaluationPrice,
                evaluationDate,
                returnPct,
                maxRunupPct,
                maxDrawdownPct,
                "MATURED",
                sourceName,
                marketTimestamp,
                calculatedAt
        );
    }

    public void replaceWith(
            OutcomeResult result,
            String sourceName,
            Instant marketTimestamp,
            Instant calculatedAt
    ) {
        this.baselinePrice = result.baselinePrice();
        this.evaluationPrice = result.evaluationPrice();
        this.evaluationDate = result.evaluationDate();
        this.returnPct = result.returnPct();
        this.maxRunupPct = result.maxRunupPct();
        this.maxDrawdownPct = result.maxDrawdownPct();
        this.status = result.status();
        this.sourceName = sourceName;
        this.marketTimestamp = marketTimestamp;
        this.calculatedAt = calculatedAt;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getBaselineType() {
        return baselineType;
    }

    public String getHorizon() {
        return horizon;
    }

    public BigDecimal getBaselinePrice() {
        return baselinePrice;
    }

    public BigDecimal getEvaluationPrice() {
        return evaluationPrice;
    }

    public LocalDate getEvaluationDate() {
        return evaluationDate;
    }

    public BigDecimal getReturnPct() {
        return returnPct;
    }

    public BigDecimal getMaxRunupPct() {
        return maxRunupPct;
    }

    public BigDecimal getMaxDrawdownPct() {
        return maxDrawdownPct;
    }

    public String getStatus() {
        return status;
    }

    public String getSourceName() {
        return sourceName;
    }

    public Instant getMarketTimestamp() {
        return marketTimestamp;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }
}

package com.aistock.research.tradefeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_trade_fill_revision")
public class TradeFillRevisionEntity {

    @Id
    @Column(name = "revision_id", nullable = false, length = 36)
    private String revisionId;

    @Column(name = "fill_id", nullable = false, length = 36)
    private String fillId;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    @Column(name = "revision_sequence", nullable = false)
    private long revisionSequence;

    @Column(name = "revision_type", nullable = false, length = 16)
    private String revisionType;

    @Column(name = "side", nullable = false, length = 8)
    private String side;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "price", nullable = false, precision = 20, scale = 6)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TradeFillRevisionEntity() {
    }

    private TradeFillRevisionEntity(
            String revisionId,
            String fillId,
            String caseId,
            long revisionSequence,
            String revisionType,
            String side,
            Instant executedAt,
            BigDecimal price,
            long quantity,
            Instant createdAt
    ) {
        this.revisionId = revisionId;
        this.fillId = fillId;
        this.caseId = caseId;
        this.revisionSequence = revisionSequence;
        this.revisionType = revisionType;
        this.side = side;
        this.executedAt = executedAt;
        this.price = price;
        this.quantity = quantity;
        this.createdAt = createdAt;
    }

    public static TradeFillRevisionEntity correction(
            String revisionId,
            String fillId,
            String caseId,
            long revisionSequence,
            String side,
            Instant executedAt,
            BigDecimal price,
            long quantity,
            Instant createdAt
    ) {
        return new TradeFillRevisionEntity(
                revisionId, fillId, caseId, revisionSequence,
                "CORRECTION", side, executedAt, price, quantity, createdAt);
    }

    public static TradeFillRevisionEntity voided(
            String revisionId,
            String fillId,
            String caseId,
            long revisionSequence,
            String side,
            Instant executedAt,
            BigDecimal price,
            long quantity,
            Instant createdAt
    ) {
        return new TradeFillRevisionEntity(
                revisionId, fillId, caseId, revisionSequence,
                "VOID", side, executedAt, price, quantity, createdAt);
    }

    public String getRevisionId() {
        return revisionId;
    }

    public String getFillId() {
        return fillId;
    }

    public String getCaseId() {
        return caseId;
    }

    public long getRevisionSequence() {
        return revisionSequence;
    }

    public String getRevisionType() {
        return revisionType;
    }

    public String getSide() {
        return side;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

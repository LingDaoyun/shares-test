package com.aistock.research.tradefeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_trade_fill")
public class TradeFillEntity {

    @Id
    @Column(name = "fill_id", nullable = false, length = 36)
    private String fillId;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TradeFillEntity() {
    }

    private TradeFillEntity(
            String fillId,
            String caseId,
            String side,
            Instant executedAt,
            BigDecimal price,
            long quantity,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.fillId = fillId;
        this.caseId = caseId;
        this.side = side;
        this.executedAt = executedAt;
        this.price = price;
        this.quantity = quantity;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static TradeFillEntity create(
            String fillId,
            String caseId,
            String side,
            Instant executedAt,
            BigDecimal price,
            long quantity,
            Instant createdAt
    ) {
        return new TradeFillEntity(fillId, caseId, side, executedAt, price, quantity, createdAt, createdAt);
    }

    public String getFillId() {
        return fillId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getSide() {
        return side;
    }

    public void revise(String side, Instant executedAt, BigDecimal price, long quantity, Instant updatedAt) {
        this.side = side;
        this.executedAt = executedAt;
        this.price = price;
        this.quantity = quantity;
        this.updatedAt = updatedAt;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

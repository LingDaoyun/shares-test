package com.aistock.research.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "special_watchlist")
public class SpecialWatchlistEntity {

    @Id
    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "note", nullable = false, length = 1000)
    private String note;

    @Column(name = "last_action_label", length = 64)
    private String lastActionLabel;

    @Column(name = "last_decision_score", precision = 8, scale = 2)
    private BigDecimal lastDecisionScore;

    @Column(name = "last_analyzed_at")
    private Instant lastAnalyzedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SpecialWatchlistEntity() {
    }

    private SpecialWatchlistEntity(String symbol, String companyName, String note, Instant now) {
        this.symbol = symbol;
        this.companyName = companyName;
        this.note = note == null ? "" : note;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static SpecialWatchlistEntity create(String symbol, String companyName, String note, Instant now) {
        return new SpecialWatchlistEntity(symbol, companyName, note, now);
    }

    public void update(String companyName, String note, Instant now) {
        this.companyName = companyName;
        this.note = note == null ? "" : note;
        this.updatedAt = now;
    }

    public void recordAnalysis(String actionLabel, BigDecimal decisionScore, Instant now) {
        this.lastActionLabel = actionLabel;
        this.lastDecisionScore = decisionScore;
        this.lastAnalyzedAt = now;
        this.updatedAt = now;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getNote() {
        return note;
    }

    public String getLastActionLabel() {
        return lastActionLabel;
    }

    public BigDecimal getLastDecisionScore() {
        return lastDecisionScore;
    }

    public Instant getLastAnalyzedAt() {
        return lastAnalyzedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

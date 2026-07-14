package com.aistock.research.v2.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "v2_quote_snapshot")
public class V2QuoteSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_stage", nullable = false, length = 32)
    private QuoteStage quoteStage;

    @Column(name = "last_price", precision = 20, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "amount", precision = 30, scale = 4)
    private BigDecimal amount;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Column(name = "source", nullable = false, length = 128)
    private String source;

    @Column(name = "source_version", nullable = false, length = 128)
    private String sourceVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false, length = 32)
    private DataQualityStatus qualityStatus;

    @Column(name = "raw_payload_hash", nullable = false, length = 64)
    private String rawPayloadHash;

    @Column(name = "raw_payload_json", nullable = false, columnDefinition = "TEXT")
    private String rawPayloadJson;

    protected V2QuoteSnapshotEntity() {
    }

    public V2QuoteSnapshotEntity(String snapshotId, String symbol, String companyName, QuoteStage quoteStage,
                                 BigDecimal lastPrice, BigDecimal amount, Instant effectiveAt, Instant availableAt,
                                 Instant ingestedAt, String source, String sourceVersion,
                                 DataQualityStatus qualityStatus, String rawPayloadHash, String rawPayloadJson) {
        this.snapshotId = snapshotId;
        this.symbol = symbol;
        this.companyName = companyName;
        this.quoteStage = quoteStage;
        this.lastPrice = lastPrice;
        this.amount = amount;
        this.effectiveAt = effectiveAt;
        this.availableAt = availableAt;
        this.ingestedAt = ingestedAt;
        this.source = source;
        this.sourceVersion = sourceVersion;
        this.qualityStatus = qualityStatus;
        this.rawPayloadHash = rawPayloadHash;
        this.rawPayloadJson = rawPayloadJson;
    }

    public String getSnapshotId() { return snapshotId; }
    public String getSymbol() { return symbol; }
    public String getCompanyName() { return companyName; }
    public QuoteStage getQuoteStage() { return quoteStage; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public BigDecimal getAmount() { return amount; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getIngestedAt() { return ingestedAt; }
    public String getSource() { return source; }
    public String getSourceVersion() { return sourceVersion; }
    public DataQualityStatus getQualityStatus() { return qualityStatus; }
    public String getRawPayloadHash() { return rawPayloadHash; }
    public String getRawPayloadJson() { return rawPayloadJson; }
}

package com.aistock.research.v2.factor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "v2_factor_snapshot")
public class V2FactorSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "strategy_code", nullable = false, length = 64)
    private String strategyCode;

    @Column(name = "strategy_version", nullable = false, length = 64)
    private String strategyVersion;

    @Column(name = "factor_code", nullable = false, length = 64)
    private String factorCode;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "raw_value", precision = 24, scale = 8)
    private BigDecimal rawValue;

    @Column(name = "normalized_value", precision = 8, scale = 2)
    private BigDecimal normalizedValue;

    @Column(name = "data_confidence_impact", nullable = false, precision = 8, scale = 2)
    private BigDecimal dataConfidenceImpact;

    @Column(name = "value_unit", nullable = false, length = 32)
    private String valueUnit;

    @Column(name = "missing_reason", nullable = false, length = 255)
    private String missingReason;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "source_snapshot_id", length = 64)
    private String sourceSnapshotId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    protected V2FactorSnapshotEntity() {
    }

    public V2FactorSnapshotEntity(String snapshotId, String strategyCode, String strategyVersion, String factorCode,
                                  String symbol, BigDecimal rawValue, BigDecimal normalizedValue,
                                  BigDecimal dataConfidenceImpact, String valueUnit, String missingReason,
                                  Instant availableAt, Instant calculatedAt, String sourceSnapshotId,
                                  String payloadJson) {
        this.snapshotId = snapshotId;
        this.strategyCode = strategyCode;
        this.strategyVersion = strategyVersion;
        this.factorCode = factorCode;
        this.symbol = symbol;
        this.rawValue = rawValue;
        this.normalizedValue = normalizedValue;
        this.dataConfidenceImpact = dataConfidenceImpact;
        this.valueUnit = valueUnit;
        this.missingReason = missingReason;
        this.availableAt = availableAt;
        this.calculatedAt = calculatedAt;
        this.sourceSnapshotId = sourceSnapshotId;
        this.payloadJson = payloadJson;
    }

    public String getSnapshotId() { return snapshotId; }
    public String getStrategyCode() { return strategyCode; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getFactorCode() { return factorCode; }
    public String getSymbol() { return symbol; }
    public BigDecimal getRawValue() { return rawValue; }
    public BigDecimal getNormalizedValue() { return normalizedValue; }
    public BigDecimal getDataConfidenceImpact() { return dataConfidenceImpact; }
    public String getValueUnit() { return valueUnit; }
    public String getMissingReason() { return missingReason; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public String getSourceSnapshotId() { return sourceSnapshotId; }
    public String getPayloadJson() { return payloadJson; }
}

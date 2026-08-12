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

@Entity
@Table(name = "short_term_signal_observation")
public class ShortTermSignalObservationEntity implements Persistable<String> {

    @Id
    @Column(name = "observation_id", nullable = false, length = 64)
    private String observationId;

    @Column(name = "publication_key", nullable = false, length = 160)
    private String publicationKey;

    @Column(name = "publication_type", nullable = false, length = 32)
    private String publicationType;

    @Column(name = "strategy_version", nullable = false, length = 80)
    private String strategyVersion;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "candidate_rank", nullable = false)
    private int candidateRank;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "signal_family", nullable = false, length = 80)
    private String signalFamily;

    @Column(name = "market_regime", nullable = false, length = 64)
    private String marketRegime;

    @Column(name = "recommendation_price", precision = 20, scale = 6)
    private BigDecimal recommendationPrice;

    @Column(name = "recommendation_trade_date")
    private LocalDate recommendationTradeDate;

    @Column(name = "data_cutoff_at")
    private Instant dataCutoffAt;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "source_name", nullable = false, length = 128)
    private String sourceName;

    @Column(name = "coverage_ratio", precision = 10, scale = 6)
    private BigDecimal coverageRatio;

    @Column(name = "coverage_expected_count", nullable = false)
    private int coverageExpectedCount;

    @Column(name = "coverage_fetched_count", nullable = false)
    private int coverageFetchedCount;

    @Column(name = "validation_eligible", nullable = false)
    private boolean validationEligible;

    @Column(name = "calibration_eligible", nullable = false)
    private boolean calibrationEligible;

    @Column(name = "validation_block_reason", length = 1000)
    private String validationBlockReason;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "buy_commission_percent", nullable = false, precision = 10, scale = 6)
    private BigDecimal buyCommissionPercent;

    @Column(name = "sell_commission_percent", nullable = false, precision = 10, scale = 6)
    private BigDecimal sellCommissionPercent;

    @Column(name = "sell_stamp_duty_percent", nullable = false, precision = 10, scale = 6)
    private BigDecimal sellStampDutyPercent;

    @Column(name = "buy_slippage_percent", nullable = false, precision = 10, scale = 6)
    private BigDecimal buySlippagePercent;

    @Column(name = "sell_slippage_percent", nullable = false, precision = 10, scale = 6)
    private BigDecimal sellSlippagePercent;

    @Column(name = "outcome_state", nullable = false, length = 32)
    private String outcomeState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity = true;

    protected ShortTermSignalObservationEntity() {
    }

    public ShortTermSignalObservationEntity(
            String observationId,
            String publicationKey,
            String publicationType,
            String strategyVersion,
            String symbol,
            String companyName,
            int candidateRank,
            String action,
            String signalFamily,
            String marketRegime,
            BigDecimal recommendationPrice,
            LocalDate recommendationTradeDate,
            Instant dataCutoffAt,
            Instant publishedAt,
            String sourceName,
            BigDecimal coverageRatio,
            int coverageExpectedCount,
            int coverageFetchedCount,
            boolean validationEligible,
            boolean calibrationEligible,
            String validationBlockReason,
            String payloadJson,
            ShortTermValidationCostAssumptions costs,
            Instant createdAt
    ) {
        ShortTermValidationCostAssumptions safeCosts = costs == null
                ? new ShortTermValidationCostAssumptions(null, null, null, null, null)
                : costs;
        this.observationId = observationId;
        this.publicationKey = publicationKey;
        this.publicationType = publicationType;
        this.strategyVersion = strategyVersion;
        this.symbol = symbol;
        this.companyName = companyName;
        this.candidateRank = candidateRank;
        this.action = action;
        this.signalFamily = signalFamily;
        this.marketRegime = marketRegime;
        this.recommendationPrice = recommendationPrice;
        this.recommendationTradeDate = recommendationTradeDate;
        this.dataCutoffAt = dataCutoffAt;
        this.publishedAt = publishedAt;
        this.sourceName = sourceName;
        this.coverageRatio = coverageRatio;
        this.coverageExpectedCount = coverageExpectedCount;
        this.coverageFetchedCount = coverageFetchedCount;
        this.validationEligible = validationEligible;
        this.calibrationEligible = calibrationEligible;
        this.validationBlockReason = validationBlockReason;
        this.payloadJson = payloadJson;
        this.buyCommissionPercent = safeCosts.buyCommissionPercent();
        this.sellCommissionPercent = safeCosts.sellCommissionPercent();
        this.sellStampDutyPercent = safeCosts.sellStampDutyPercent();
        this.buySlippagePercent = safeCosts.buySlippagePercent();
        this.sellSlippagePercent = safeCosts.sellSlippagePercent();
        this.outcomeState = validationEligible ? "PENDING" : "NOT_ELIGIBLE";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newEntity = false;
    }

    public void markOutcomesComplete(Instant completedAt) {
        if (!validationEligible) {
            return;
        }
        outcomeState = "COMPLETE";
        updatedAt = completedAt;
    }

    public ShortTermValidationCostAssumptions costAssumptions() {
        return new ShortTermValidationCostAssumptions(
                buyCommissionPercent,
                sellCommissionPercent,
                sellStampDutyPercent,
                buySlippagePercent,
                sellSlippagePercent
        );
    }

    @Override
    public String getId() { return observationId; }
    @Override
    public boolean isNew() { return newEntity; }
    public String getObservationId() { return observationId; }
    public String getPublicationKey() { return publicationKey; }
    public String getPublicationType() { return publicationType; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getSymbol() { return symbol; }
    public String getCompanyName() { return companyName; }
    public int getCandidateRank() { return candidateRank; }
    public String getAction() { return action; }
    public String getSignalFamily() { return signalFamily; }
    public String getMarketRegime() { return marketRegime; }
    public BigDecimal getRecommendationPrice() { return recommendationPrice; }
    public LocalDate getRecommendationTradeDate() { return recommendationTradeDate; }
    public Instant getDataCutoffAt() { return dataCutoffAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getSourceName() { return sourceName; }
    public BigDecimal getCoverageRatio() { return coverageRatio; }
    public int getCoverageExpectedCount() { return coverageExpectedCount; }
    public int getCoverageFetchedCount() { return coverageFetchedCount; }
    public boolean isValidationEligible() { return validationEligible; }
    public boolean isCalibrationEligible() { return calibrationEligible; }
    public String getValidationBlockReason() { return validationBlockReason; }
    public String getPayloadJson() { return payloadJson; }
    public BigDecimal getBuyCommissionPercent() { return buyCommissionPercent; }
    public BigDecimal getSellCommissionPercent() { return sellCommissionPercent; }
    public BigDecimal getSellStampDutyPercent() { return sellStampDutyPercent; }
    public BigDecimal getBuySlippagePercent() { return buySlippagePercent; }
    public BigDecimal getSellSlippagePercent() { return sellSlippagePercent; }
    public String getOutcomeState() { return outcomeState; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

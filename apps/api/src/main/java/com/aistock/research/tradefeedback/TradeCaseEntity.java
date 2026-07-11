package com.aistock.research.tradefeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_trade_case")
public class TradeCaseEntity {

    @Id
    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    @Column(name = "recommendation_fingerprint", nullable = false, length = 64)
    private String recommendationFingerprint;

    @Column(name = "decision_id", length = 36)
    private String decisionId;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "source_module", nullable = false, length = 64)
    private String sourceModule;

    @Column(name = "recommendation_action", nullable = false, length = 64)
    private String recommendationAction;

    @Column(name = "recommendation_score", precision = 8, scale = 2)
    private BigDecimal recommendationScore;

    @Column(name = "rule_version", nullable = false, length = 64)
    private String ruleVersion;

    @Column(name = "recommended_price", nullable = false, precision = 20, scale = 6)
    private BigDecimal recommendedPrice;

    @Column(name = "recommended_at", nullable = false)
    private Instant recommendedAt;

    @Column(name = "recommendation_payload_json", nullable = false, columnDefinition = "TEXT")
    private String recommendationPayloadJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TradeCaseEntity() {
    }

    private TradeCaseEntity(
            String caseId,
            String recommendationFingerprint,
            String decisionId,
            String symbol,
            String companyName,
            String sourceModule,
            String recommendationAction,
            BigDecimal recommendationScore,
            String ruleVersion,
            BigDecimal recommendedPrice,
            Instant recommendedAt,
            String recommendationPayloadJson,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.caseId = caseId;
        this.recommendationFingerprint = recommendationFingerprint;
        this.decisionId = decisionId;
        this.symbol = symbol;
        this.companyName = companyName;
        this.sourceModule = sourceModule;
        this.recommendationAction = recommendationAction;
        this.recommendationScore = recommendationScore;
        this.ruleVersion = ruleVersion;
        this.recommendedPrice = recommendedPrice;
        this.recommendedAt = recommendedAt;
        this.recommendationPayloadJson = recommendationPayloadJson;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static TradeCaseEntity planned(
            String caseId,
            String recommendationFingerprint,
            String decisionId,
            String symbol,
            String companyName,
            String sourceModule,
            String recommendationAction,
            BigDecimal recommendationScore,
            String ruleVersion,
            BigDecimal recommendedPrice,
            Instant recommendedAt,
            String recommendationPayloadJson,
            Instant createdAt
    ) {
        return new TradeCaseEntity(
                caseId,
                recommendationFingerprint,
                decisionId,
                symbol,
                companyName,
                sourceModule,
                recommendationAction,
                recommendationScore,
                ruleVersion,
                recommendedPrice,
                recommendedAt,
                recommendationPayloadJson,
                "PLANNED",
                createdAt,
                createdAt
        );
    }

    public String getCaseId() {
        return caseId;
    }

    public String getRecommendationFingerprint() {
        return recommendationFingerprint;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getSourceModule() {
        return sourceModule;
    }

    public String getRecommendationAction() {
        return recommendationAction;
    }

    public BigDecimal getRecommendationScore() {
        return recommendationScore;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public BigDecimal getRecommendedPrice() {
        return recommendedPrice;
    }

    public Instant getRecommendedAt() {
        return recommendedAt;
    }

    public String getRecommendationPayloadJson() {
        return recommendationPayloadJson;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

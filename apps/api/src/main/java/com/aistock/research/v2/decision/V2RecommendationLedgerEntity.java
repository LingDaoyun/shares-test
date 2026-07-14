package com.aistock.research.v2.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "v2_recommendation_ledger")
public class V2RecommendationLedgerEntity {

    @Id
    @Column(name = "ledger_id", nullable = false, length = 64)
    private String ledgerId;

    @Column(name = "recommendation_fingerprint", nullable = false, unique = true, length = 64)
    private String recommendationFingerprint;

    @Column(name = "strategy_code", nullable = false, length = 64)
    private String strategyCode;

    @Column(name = "strategy_version", nullable = false, length = 64)
    private String strategyVersion;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "decision_at", nullable = false)
    private Instant decisionAt;

    @Column(name = "data_cutoff_at", nullable = false)
    private Instant dataCutoffAt;

    @Column(name = "candidate_stage", nullable = false, length = 32)
    private String candidateStage;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "rank_score", precision = 8, scale = 2)
    private BigDecimal rankScore;

    @Column(name = "data_confidence", precision = 8, scale = 2)
    private BigDecimal dataConfidence;

    @Column(name = "historical_hit_rate", precision = 8, scale = 2)
    private BigDecimal historicalHitRate;

    @Column(name = "risk_reward", precision = 8, scale = 2)
    private BigDecimal riskReward;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected V2RecommendationLedgerEntity() {
    }

    public V2RecommendationLedgerEntity(String ledgerId, String recommendationFingerprint, String strategyCode,
                                        String strategyVersion, String symbol, String companyName,
                                        Instant decisionAt, Instant dataCutoffAt, String candidateStage,
                                        String action, BigDecimal rankScore, BigDecimal dataConfidence,
                                        BigDecimal historicalHitRate, BigDecimal riskReward,
                                        String payloadJson, Instant createdAt) {
        this.ledgerId = ledgerId;
        this.recommendationFingerprint = recommendationFingerprint;
        this.strategyCode = strategyCode;
        this.strategyVersion = strategyVersion;
        this.symbol = symbol;
        this.companyName = companyName;
        this.decisionAt = decisionAt;
        this.dataCutoffAt = dataCutoffAt;
        this.candidateStage = candidateStage;
        this.action = action;
        this.rankScore = rankScore;
        this.dataConfidence = dataConfidence;
        this.historicalHitRate = historicalHitRate;
        this.riskReward = riskReward;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public String getLedgerId() { return ledgerId; }
    public String getRecommendationFingerprint() { return recommendationFingerprint; }
    public String getStrategyCode() { return strategyCode; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getSymbol() { return symbol; }
    public String getCompanyName() { return companyName; }
    public Instant getDecisionAt() { return decisionAt; }
    public Instant getDataCutoffAt() { return dataCutoffAt; }
    public String getCandidateStage() { return candidateStage; }
    public String getAction() { return action; }
    public BigDecimal getRankScore() { return rankScore; }
    public BigDecimal getDataConfidence() { return dataConfidence; }
    public BigDecimal getHistoricalHitRate() { return historicalHitRate; }
    public BigDecimal getRiskReward() { return riskReward; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.aistock.research.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "investment_decision_history")
public class DecisionHistoryEntity {

    @Id
    @Column(name = "decision_id", nullable = false, length = 36)
    private String decisionId;

    @Column(name = "analysis_id", nullable = false, length = 36)
    private String analysisId;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(name = "action_stage", nullable = false, length = 64)
    private String actionStage;

    @Column(name = "action_label", nullable = false, length = 64)
    private String actionLabel;

    @Column(name = "decision_score", precision = 8, scale = 2)
    private BigDecimal decisionScore;

    @Column(name = "rule_version", nullable = false, length = 64)
    private String ruleVersion;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "data_as_of", nullable = false)
    private Instant dataAsOf;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected DecisionHistoryEntity() {
    }

    public DecisionHistoryEntity(
            String decisionId,
            String analysisId,
            String symbol,
            String sourceType,
            String actionStage,
            String actionLabel,
            BigDecimal decisionScore,
            String ruleVersion,
            String payloadJson,
            Instant dataAsOf,
            Instant recordedAt
    ) {
        this.decisionId = decisionId;
        this.analysisId = analysisId;
        this.symbol = symbol;
        this.sourceType = sourceType;
        this.actionStage = actionStage;
        this.actionLabel = actionLabel;
        this.decisionScore = decisionScore;
        this.ruleVersion = ruleVersion;
        this.payloadJson = payloadJson;
        this.dataAsOf = dataAsOf;
        this.recordedAt = recordedAt;
    }

    public DecisionHistoryEntry toEntry() {
        return new DecisionHistoryEntry(
                decisionId,
                analysisId,
                symbol,
                sourceType,
                actionStage,
                actionLabel,
                decisionScore,
                ruleVersion,
                dataAsOf,
                recordedAt
        );
    }
}

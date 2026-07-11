package com.aistock.research.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "research_analysis_history")
public class AnalysisHistoryEntity {

    @Id
    @Column(name = "analysis_id", nullable = false, length = 36)
    private String analysisId;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "analysis_type", nullable = false, length = 64)
    private String analysisType;

    @Column(name = "status", nullable = false, length = 64)
    private String status;

    @Column(name = "summary", nullable = false, length = 2000)
    private String summary;

    @Column(name = "ai_provider", length = 64)
    private String aiProvider;

    @Column(name = "ai_model", length = 128)
    private String aiModel;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "data_as_of", nullable = false)
    private Instant dataAsOf;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected AnalysisHistoryEntity() {
    }

    public AnalysisHistoryEntity(
            String analysisId,
            String symbol,
            String companyName,
            String analysisType,
            String status,
            String summary,
            String aiProvider,
            String aiModel,
            String payloadJson,
            Instant dataAsOf,
            Instant recordedAt
    ) {
        this.analysisId = analysisId;
        this.symbol = symbol;
        this.companyName = companyName;
        this.analysisType = analysisType;
        this.status = status;
        this.summary = summary;
        this.aiProvider = aiProvider;
        this.aiModel = aiModel;
        this.payloadJson = payloadJson;
        this.dataAsOf = dataAsOf;
        this.recordedAt = recordedAt;
    }

    public String getAnalysisId() {
        return analysisId;
    }
}

package com.aistock.research.committee;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AgentConsensusReport(
        String symbol,
        String companyName,
        String consensusStage,
        String consensusLabel,
        BigDecimal consensusScore,
        String consensusReason,
        int supportCount,
        int watchCount,
        int reviewCount,
        int vetoCount,
        List<AgentOpinion> opinions,
        List<String> agreements,
        List<String> disagreements,
        List<String> requiredEvidence,
        Instant generatedAt,
        boolean aiEnhanced,
        String aiProvider,
        String aiModel,
        String aiSummary,
        String aiSuggestedStage,
        List<String> aiWarnings
) {
    public AgentConsensusReport(
            String symbol,
            String companyName,
            String consensusStage,
            String consensusLabel,
            BigDecimal consensusScore,
            String consensusReason,
            int supportCount,
            int watchCount,
            int reviewCount,
            int vetoCount,
            List<AgentOpinion> opinions,
            List<String> agreements,
            List<String> disagreements,
            List<String> requiredEvidence,
            Instant generatedAt
    ) {
        this(
                symbol,
                companyName,
                consensusStage,
                consensusLabel,
                consensusScore,
                consensusReason,
                supportCount,
                watchCount,
                reviewCount,
                vetoCount,
                opinions,
                agreements,
                disagreements,
                requiredEvidence,
                generatedAt,
                false,
                null,
                null,
                null,
                null,
                List.of()
        );
    }

    public AgentConsensusReport withAiEnhancement(
            String provider,
            String model,
            String summary,
            String suggestedStage,
            List<AgentOpinion> enhancedOpinions,
            List<String> warnings
    ) {
        return new AgentConsensusReport(
                symbol,
                companyName,
                consensusStage,
                consensusLabel,
                consensusScore,
                consensusReason,
                supportCount,
                watchCount,
                reviewCount,
                vetoCount,
                enhancedOpinions,
                agreements,
                disagreements,
                requiredEvidence,
                generatedAt,
                warnings == null || warnings.isEmpty(),
                provider,
                model,
                summary,
                suggestedStage,
                warnings == null ? List.of() : warnings
        );
    }

    public AgentConsensusReport withAiWarning(String provider, String model, String warning) {
        return withAiEnhancement(provider, model, null, null, opinions, List.of(warning));
    }
}

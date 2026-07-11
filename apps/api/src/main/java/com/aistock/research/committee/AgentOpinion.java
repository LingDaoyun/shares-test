package com.aistock.research.committee;

import com.aistock.research.evidence.AgentEvidenceCheck;

import java.math.BigDecimal;
import java.util.List;

public record AgentOpinion(
        String agentCode,
        String agentName,
        String perspective,
        String vote,
        String voteLabel,
        BigDecimal confidence,
        BigDecimal score,
        List<String> supports,
        List<String> objections,
        List<String> requiredEvidence,
        List<AgentEvidenceCheck> evidenceChecks,
        String aiArgument,
        String aiCounterEvidence,
        String aiConfidenceNote
) {
    public AgentOpinion(
            String agentCode,
            String agentName,
            String perspective,
            String vote,
            String voteLabel,
            BigDecimal confidence,
            BigDecimal score,
            List<String> supports,
            List<String> objections,
            List<String> requiredEvidence
    ) {
        this(
                agentCode,
                agentName,
                perspective,
                vote,
                voteLabel,
                confidence,
                score,
                supports,
                objections,
                requiredEvidence,
                List.of(),
                null,
                null,
                null
        );
    }

    public AgentOpinion withAiArgument(String argument, String counterEvidence, String confidenceNote) {
        return new AgentOpinion(
                agentCode,
                agentName,
                perspective,
                vote,
                voteLabel,
                confidence,
                score,
                supports,
                objections,
                requiredEvidence,
                evidenceChecks,
                argument,
                counterEvidence,
                confidenceNote
        );
    }
}

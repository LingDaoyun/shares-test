package com.aistock.research.review;

import com.aistock.research.committee.AgentConsensusReport;

import java.time.Instant;
import java.util.List;

public record EvidenceReviewReport(
        String symbol,
        String companyName,
        String reviewStage,
        String reviewLabel,
        int totalItems,
        int verifiedCount,
        int partialCount,
        int notFoundCount,
        int blockedCount,
        AgentConsensusReport consensus,
        List<EvidenceReviewItem> items,
        List<EvidenceReviewStep> steps,
        List<String> conclusions,
        Instant generatedAt
) {
}

package com.aistock.research.v2.strategy;

import java.time.Instant;

public record AgentEvidenceFinding(
        String agentName,
        String role,
        AgentEvidenceVote vote,
        String sourceUrl,
        String sourceTitle,
        Instant publishedAt,
        String evidenceHash,
        String claim
) {
}

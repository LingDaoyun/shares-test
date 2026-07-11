package com.aistock.research.evidence;

public record AgentEvidenceCheck(
        String requirement,
        String status,
        String statusLabel,
        String source,
        String evidenceText,
        String url,
        int confidence
) {
}

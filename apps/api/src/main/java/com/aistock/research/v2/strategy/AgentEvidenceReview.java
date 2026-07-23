package com.aistock.research.v2.strategy;

import java.util.List;

public record AgentEvidenceReview(
        List<AgentEvidenceFinding> findings,
        int supportCount,
        int opposeCount,
        int abstainCount,
        int sourceOverlapCount,
        boolean hasConflict,
        List<String> warnings
) {
    public AgentEvidenceReview {
        findings = findings == null ? List.of() : List.copyOf(findings);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

package com.aistock.research.configuration;

import java.time.Instant;
import java.util.List;

public record RuntimeConfigState(
        StoredLlmConfig llm,
        long llmRevision,
        List<PolicySourceConfig> policySources,
        long policySourcesRevision,
        Instant updatedAt
) {

    public RuntimeConfigState {
        policySources = policySources == null ? List.of() : List.copyOf(policySources);
    }
}

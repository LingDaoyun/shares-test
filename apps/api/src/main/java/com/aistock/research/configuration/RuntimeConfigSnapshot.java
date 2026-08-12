package com.aistock.research.configuration;

import jakarta.validation.Valid;

import java.time.Instant;
import java.util.List;

public record RuntimeConfigSnapshot(
        String storage,
        long llmRevision,
        long policySourcesRevision,
        @Valid LlmRuntimeConfig llm,
        List<@Valid PolicySourceConfig> policySources,
        Instant updatedAt
) {

    public RuntimeConfigSnapshot {
        policySources = policySources == null ? null : List.copyOf(policySources);
    }
}

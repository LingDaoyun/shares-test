package com.aistock.research.configuration;

import java.time.Instant;
import java.util.List;

public record RuntimeConfigSnapshot(
        String dataId,
        String group,
        LlmRuntimeConfig llm,
        List<PolicySourceConfig> policySources,
        Instant updatedAt
) {
}

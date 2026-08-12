package com.aistock.research.configuration;

import java.util.List;

public record StoredPolicySources(List<PolicySourceConfig> sources) {

    public StoredPolicySources {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

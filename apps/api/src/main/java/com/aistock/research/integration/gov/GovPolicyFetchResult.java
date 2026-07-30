package com.aistock.research.integration.gov;

import java.util.List;

public record GovPolicyFetchResult(
        List<GovPolicyItem> items,
        List<String> failedSources
) {
    public GovPolicyFetchResult {
        items = items == null ? List.of() : List.copyOf(items);
        failedSources = failedSources == null ? List.of() : List.copyOf(failedSources);
    }
}

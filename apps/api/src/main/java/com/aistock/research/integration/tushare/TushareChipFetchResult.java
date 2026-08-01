package com.aistock.research.integration.tushare;

import com.aistock.research.shortterm.chip.ExternalChipPerformance;

import java.util.Optional;

public record TushareChipFetchResult(
        Optional<ExternalChipPerformance> value,
        String errorSummary,
        Integer httpStatus,
        int retryCount
) {
    public TushareChipFetchResult {
        value = value == null ? Optional.empty() : value;
    }

    public static TushareChipFetchResult success(ExternalChipPerformance value, int status) {
        return new TushareChipFetchResult(Optional.of(value), null, status, 0);
    }

    public static TushareChipFetchResult failure(String summary, Integer status) {
        return new TushareChipFetchResult(Optional.empty(), summary, status, 0);
    }
}

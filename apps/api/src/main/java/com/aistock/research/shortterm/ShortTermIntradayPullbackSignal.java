package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ShortTermIntradayPullbackSignal(
        String status,
        String statusLabel,
        LocalDateTime pullbackMinute,
        LocalDateTime breakoutMinute,
        BigDecimal platformHigh,
        BigDecimal pullbackLow,
        BigDecimal pullbackVolumeRatio,
        BigDecimal breakoutVolumeRatio,
        List<String> reasons,
        List<String> dataGaps
) {
    public ShortTermIntradayPullbackSignal {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public boolean confirmed() {
        return "CONFIRMED".equals(status);
    }
}

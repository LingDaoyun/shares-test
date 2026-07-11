package com.aistock.research.dailysignal;

import java.time.LocalDate;
import java.util.List;

public record DailyMarketContext(
        String region,
        LocalDate tradeDate,
        String summary,
        List<String> riskTags,
        String positionCap,
        String source
) {
}

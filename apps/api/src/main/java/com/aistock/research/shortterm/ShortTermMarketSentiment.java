package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermMarketSentiment(
        String phase,
        BigDecimal score,
        int advancing,
        int declining,
        int limitUpLike,
        int limitDownLike,
        BigDecimal breadthPercent,
        String explanation
) {
}

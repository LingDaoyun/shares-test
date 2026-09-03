package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 炸板池中的标准化股票行，供涨停看板与潜力龙头复用。
 */
public record ShortTermBrokenBoardStock(
        String symbol,
        String name,
        String industry,
        BigDecimal latestPrice,
        BigDecimal limitUpPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        LocalTime firstSealTime,
        int sealBreakCount,
        boolean currentlySealed
) {
}

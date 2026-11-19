package com.aistock.research.shortterm;

import java.time.LocalTime;

public record ShortTermLimitDownStock(
        String symbol,
        String name,
        String industry,
        Integer consecutiveBoards,
        LocalTime lastSealTime
) {
}

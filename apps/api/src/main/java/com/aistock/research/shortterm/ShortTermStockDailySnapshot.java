package com.aistock.research.shortterm;

import com.aistock.research.firstboard.FirstBoardBar;

import java.time.Instant;
import java.util.List;

public record ShortTermStockDailySnapshot(
        String symbol,
        String adjustMode,
        String volumeUnit,
        String source,
        Instant fetchedAt,
        List<FirstBoardBar> bars
) {}

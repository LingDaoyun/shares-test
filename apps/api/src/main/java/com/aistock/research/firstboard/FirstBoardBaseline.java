package com.aistock.research.firstboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

record FirstBoardBaseline(
        String symbol,
        String name,
        String industry,
        FirstBoardBoard board,
        LocalDate baselineDate,
        BigDecimal previousClose,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal ma20Slope,
        BigDecimal rsi14,
        BigDecimal macdHistogram,
        int technicalPassCount,
        BigDecimal previousDayVolumeRatio,
        BigDecimal recentFiveMaxVolumeRatio,
        BigDecimal upperShadowRatio,
        BigDecimal threeDayCloseRange,
        boolean previousLimitUp,
        List<FirstBoardBar> recentBars
) {
    FirstBoardBaseline {
        recentBars = recentBars == null ? List.of() : List.copyOf(recentBars);
    }

    FirstBoardBaseline withRecentBars(List<FirstBoardBar> bars) {
        return new FirstBoardBaseline(
                symbol, name, industry, board, baselineDate, previousClose, ma10, ma20, ma20Slope,
                rsi14, macdHistogram, technicalPassCount, previousDayVolumeRatio,
                recentFiveMaxVolumeRatio, upperShadowRatio, threeDayCloseRange,
                previousLimitUp, bars);
    }
}

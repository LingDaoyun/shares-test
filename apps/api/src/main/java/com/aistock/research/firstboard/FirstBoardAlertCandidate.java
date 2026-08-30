package com.aistock.research.firstboard;

import java.math.BigDecimal;

public record FirstBoardAlertCandidate(
        String symbol,
        String name,
        String industry,
        FirstBoardBoard board,
        FirstBoardAlertLane lane,
        FirstBoardAlertLevel level,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal ma20Slope,
        BigDecimal rsi14,
        BigDecimal macdHistogram,
        int technicalPassCount,
        BigDecimal realtimeVolumeRatio,
        BigDecimal previousDayVolumeRatio,
        BigDecimal recentFiveMaxVolumeRatio,
        BigDecimal upperShadowRatio,
        BigDecimal intradayDrawdown,
        BigDecimal threeDayCloseRange
) {
}

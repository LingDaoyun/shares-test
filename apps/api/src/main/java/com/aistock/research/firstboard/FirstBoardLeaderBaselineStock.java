package com.aistock.research.firstboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 面向潜力龙头模块的只读技术基线，不暴露内部持久化实体。
 */
public record FirstBoardLeaderBaselineStock(
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
    public FirstBoardLeaderBaselineStock {
        recentBars = recentBars == null ? List.of() : List.copyOf(recentBars);
    }

    static FirstBoardLeaderBaselineStock from(FirstBoardBaseline baseline) {
        return new FirstBoardLeaderBaselineStock(
                baseline.symbol(), baseline.name(), baseline.industry(), baseline.board(),
                baseline.baselineDate(), baseline.previousClose(), baseline.ma10(), baseline.ma20(),
                baseline.ma20Slope(), baseline.rsi14(), baseline.macdHistogram(),
                baseline.technicalPassCount(), baseline.previousDayVolumeRatio(),
                baseline.recentFiveMaxVolumeRatio(), baseline.upperShadowRatio(),
                baseline.threeDayCloseRange(), baseline.previousLimitUp(), baseline.recentBars());
    }
}

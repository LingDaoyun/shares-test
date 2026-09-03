package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardBar;

import java.time.LocalDate;
import java.util.List;

public record LeaderBarSeries(
        String symbol,
        LocalDate tradeDate,
        LocalDate baselineDate,
        String adjustMode,
        List<FirstBoardBar> bars
) {
    public LeaderBarSeries {
        adjustMode = "QFQ";
        bars = bars == null ? List.of() : List.copyOf(bars);
    }
}

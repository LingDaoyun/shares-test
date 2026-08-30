package com.aistock.research.firstboard;

import java.time.LocalDate;
import java.util.List;

public record FirstBoardBarSeries(
        String symbol,
        LocalDate tradeDate,
        LocalDate baselineDate,
        List<FirstBoardBar> bars
) {
    public FirstBoardBarSeries {
        bars = bars == null ? List.of() : List.copyOf(bars);
    }
}

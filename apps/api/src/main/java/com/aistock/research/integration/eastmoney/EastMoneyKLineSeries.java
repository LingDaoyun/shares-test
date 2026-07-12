package com.aistock.research.integration.eastmoney;

import java.util.List;

public record EastMoneyKLineSeries(
        List<EastMoneyKLine> rows,
        String sourceName,
        boolean complete,
        String detail
) {
    public EastMoneyKLineSeries {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}

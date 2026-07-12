package com.aistock.research.tradefeedback;

import java.util.List;

public record MarketKLineSeries(
        List<MarketBar> rows,
        String sourceName,
        boolean complete,
        String detail
) {
    public MarketKLineSeries {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static MarketKLineSeries complete(List<MarketBar> rows, String sourceName) {
        return new MarketKLineSeries(rows, sourceName, true, null);
    }

    public static MarketKLineSeries unavailable(String sourceName, String detail) {
        return new MarketKLineSeries(List.of(), sourceName, false, detail);
    }
}

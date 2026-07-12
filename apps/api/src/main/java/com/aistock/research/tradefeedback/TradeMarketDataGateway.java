package com.aistock.research.tradefeedback;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TradeMarketDataGateway {

    List<MarketBar> dailyKLines(String symbol, LocalDate begin, LocalDate end);

    default MarketKLineSeries dailyKLineSeries(String symbol, LocalDate begin, LocalDate end) {
        return MarketKLineSeries.complete(dailyKLines(symbol, begin, end), "UNSPECIFIED_DAILY_KLINE");
    }

    Optional<LatestMarketPrice> latestPrice(String symbol);
}

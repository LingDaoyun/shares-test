package com.aistock.research.tradefeedback;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TradeMarketDataGateway {

    List<MarketBar> dailyKLines(String symbol, LocalDate begin, LocalDate end);

    Optional<LatestMarketPrice> latestPrice(String symbol);
}

package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyLimitUpPoolEntry;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
class EastMoneyFirstBoardMarketDataGateway implements FirstBoardMarketDataGateway {

    private static final int FULL_MARKET_LIMIT = 6_000;
    private final EastMoneyClient client;

    EastMoneyFirstBoardMarketDataGateway(EastMoneyClient client) {
        this.client = client;
    }

    @Override
    public AshareQuoteSnapshot fullMarketQuotes() {
        return client.fetchAshareQuoteSnapshot(FULL_MARKET_LIMIT);
    }

    @Override
    public List<EastMoneyKLine> dailyBars(String symbol, LocalDate start, LocalDate end) {
        return client.fetchDailyKLines(symbol, start, end);
    }

    @Override
    public List<EastMoneyLimitUpPoolEntry> limitUpPool(LocalDate tradeDate) {
        return client.fetchLimitUpPool(tradeDate);
    }
}

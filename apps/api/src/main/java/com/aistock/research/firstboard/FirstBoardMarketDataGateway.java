package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyLimitUpPoolEntry;

import java.time.LocalDate;
import java.util.List;

interface FirstBoardMarketDataGateway {
    AshareQuoteSnapshot fullMarketQuotes();

    List<EastMoneyKLine> dailyBars(String symbol, LocalDate start, LocalDate end);

    List<EastMoneyLimitUpPoolEntry> limitUpPool(LocalDate tradeDate);
}

package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface FirstBoardDailyBarStore {
    int importLatestHistoryVersions();

    void upsertBars(List<EastMoneyKLine> rows, String sourceName);

    void confirmLimitUpPool(LocalDate tradeDate, Set<String> limitUpSymbols);

    Map<String, Integer> completedBarCounts(Collection<String> symbols, LocalDate endDate, int maximumBars);

    Map<String, BigDecimal> closingPrices(Collection<String> symbols, LocalDate tradeDate);

    Set<String> confirmedLimitUpSymbols(LocalDate tradeDate);

    List<EastMoneyKLine> recentBars(String symbol, LocalDate endDate, int maximumBars);

    Optional<FirstBoardMarketDay> marketDay(LocalDate tradeDate);

    Optional<LocalDate> latestReadyMarketDay();

    void saveMarketDay(FirstBoardMarketDay marketDay);

    String calculateBarDataVersion(LocalDate tradeDate);
}

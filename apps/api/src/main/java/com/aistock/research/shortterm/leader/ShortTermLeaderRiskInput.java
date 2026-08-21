package com.aistock.research.shortterm.leader;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermHotDirection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ShortTermLeaderRiskInput(
        LocalDate tradeDate,
        Instant capturedAt,
        ShortTermCoverageSnapshot coverage,
        List<EastMoneyQuote> quotes,
        List<ShortTermHotDirection> hotDirections,
        List<String> candidateIndustries
) {

    public ShortTermLeaderRiskInput {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
        hotDirections = hotDirections == null ? List.of() : List.copyOf(hotDirections);
        candidateIndustries = candidateIndustries == null ? List.of() : List.copyOf(candidateIndustries);
    }
}

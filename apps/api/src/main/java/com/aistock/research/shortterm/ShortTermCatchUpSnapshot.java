package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyRelatedBoard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 按需采样的供应商共同板块观察；归属与价格事实不构成事件受益或交易建议。 */
public record ShortTermCatchUpSnapshot(
        LocalDate tradeDate, String industry, String leaderSymbol, String boardCode, String boardName,
        Instant fetchedAt, Instant marketTimestamp, String status, ShortTermLimitUpStock leader,
        List<EastMoneyRelatedBoard> relatedBoards, String quoteSource,
        int expectedMemberCount, int fetchedMemberCount, int validQuoteCount, int historyValidCount,
        BigDecimal historyCoveragePercent, Integer risingCount, Integer sealedCount,
        int eligibleCandidateCount, int displayedCount, int truncatedCount,
        List<LocalDate> historyDates, LocalDate nextTradingDate,
        List<ShortTermCatchUpCandidate> candidates, List<String> evidence,
        List<String> contraryEvidence, List<String> dataGaps
) {
    public ShortTermCatchUpSnapshot {
        relatedBoards = relatedBoards == null ? List.of() : List.copyOf(relatedBoards);
        historyDates = historyDates == null ? List.of() : List.copyOf(historyDates);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        contraryEvidence = contraryEvidence == null ? List.of() : List.copyOf(contraryEvidence);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}

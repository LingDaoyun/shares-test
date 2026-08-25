package com.aistock.research.market;

import java.time.Instant;
import java.util.List;

/**
 * 外围市场看板快照：全球指数速览 + 美/日/韩三个市场块。
 * 东财全球行情为主数据（失败即整体不可用）；Yahoo 日韩行业为辅数据，
 * 失败时对应市场块降级说明并记入 dataGaps。
 */
public record OverseasMarketBoardSnapshot(
        Instant generatedAt,
        boolean available,
        String unavailableReason,
        List<OverseasIndexQuote> globalIndices,
        List<OverseasMarketSection> markets,
        List<String> dataGaps
) {
    public OverseasMarketBoardSnapshot {
        globalIndices = globalIndices == null ? List.of() : List.copyOf(globalIndices);
        markets = markets == null ? List.of() : List.copyOf(markets);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static OverseasMarketBoardSnapshot unavailable(Instant generatedAt, String reason) {
        return new OverseasMarketBoardSnapshot(generatedAt, false, reason, List.of(), List.of(), List.of());
    }
}

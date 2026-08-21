package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

/**
 * 涨停看板的行业聚合统计：当日该行业涨停家数、连板高度与领涨股。
 */
public record ShortTermLimitUpIndustryStat(
        String industry,
        int limitUpCount,
        int maxConsecutiveBoards,
        BigDecimal totalAmount,
        List<String> leaders
) {
    public ShortTermLimitUpIndustryStat {
        leaders = leaders == null ? List.of() : List.copyOf(leaders);
    }
}

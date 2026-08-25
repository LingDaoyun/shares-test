package com.aistock.research.market;

import java.math.BigDecimal;

/**
 * 高股息样本的行业聚合：行业内达标家数与最高股息率。
 */
public record HighDividendIndustryStat(
        String industry,
        int stockCount,
        BigDecimal topYield
) {
}

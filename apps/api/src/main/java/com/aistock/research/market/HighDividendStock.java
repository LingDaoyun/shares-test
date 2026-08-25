package com.aistock.research.market;

import java.math.BigDecimal;

/**
 * 高股息看板中的一只股票：最新完整年报的股息率与分红方案，叠加实时行情快照。
 *
 * <p>dividendYield 为小数形式（0.055 = 5.5%）；payoutRatio = 每股现金分红/每股收益，
 * 为 null 表示分红方案无法解析或收益为负，需人工核对；行情字段补充失败时可能为空。
 */
public record HighDividendStock(
        int rank,
        String symbol,
        String name,
        String market,
        String industry,
        BigDecimal dividendYield,
        BigDecimal payoutRatio,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal roe,
        String dividendPlanDescription
) {
}

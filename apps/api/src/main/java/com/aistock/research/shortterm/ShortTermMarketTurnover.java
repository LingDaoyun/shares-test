package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 大盘量能快照：按上证指数 + 深证综指的成交量（手）合计做今日与前一日对比，
 * 判断增量/缩量；今日两市成交额来自实时行情，只作规模参考，不参与历史对比。
 *
 * <p>口径：变化率 = (今日量 - 昨日量) / 昨日量。阈值 ±3% 内为平量，
 * ±10% 之外为显著增量/缩量，其间为温和增量/缩量。
 */
public record ShortTermMarketTurnover(
        LocalDate tradeDate,
        BigDecimal todayVolumeHands,
        BigDecimal previousVolumeHands,
        BigDecimal volumeChangePercent,
        BigDecimal todayAmountYuan,
        String label,
        String explanation
) {
}

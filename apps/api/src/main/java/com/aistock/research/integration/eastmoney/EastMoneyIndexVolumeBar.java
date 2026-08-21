package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 指数日 K 的量能条：只保留成交量（手），用于大盘增量/缩量对比。
 */
public record EastMoneyIndexVolumeBar(
        LocalDate tradeDate,
        BigDecimal volumeHands
) {
}

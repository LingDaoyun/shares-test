package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;

public record ShortTermIndustryRisingStock(
        String symbol, String name, BigDecimal latestPrice, BigDecimal changePercent,
        BigDecimal amount, BigDecimal turnoverRate, Instant marketTimestamp
) {}

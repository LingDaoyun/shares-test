package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketTheme(
        String code, String name, BigDecimal changePercent, BigDecimal amount,
        Integer advancing, Integer declining, Integer unchanged, BigDecimal advancingRatioPercent,
        Instant marketTimestamp
) {}

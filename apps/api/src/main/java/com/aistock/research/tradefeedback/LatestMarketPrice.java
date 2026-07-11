package com.aistock.research.tradefeedback;

import java.math.BigDecimal;

public record LatestMarketPrice(BigDecimal price, String source) {
}

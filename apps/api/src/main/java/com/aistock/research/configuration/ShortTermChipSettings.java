package com.aistock.research.configuration;

import com.aistock.research.shortterm.chip.ChipActivationMode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

@Component
public class ShortTermChipSettings {

    private static final String PREFIX = "research.short-term.chip.";

    private final Environment environment;

    public ShortTermChipSettings(Environment environment) {
        this.environment = environment;
    }

    public boolean enabled() {
        return bool("enabled", true);
    }

    public int lookbackBars() {
        return integer("lookback-bars", 120, 80, 500);
    }

    public int priceBuckets() {
        return integer("price-buckets", 150, 50, 500);
    }

    public int displayBuckets() {
        return integer("display-buckets", 60, 20, 150);
    }

    public int maxConcentrationZones() {
        return integer("max-concentration-zones", 3, 1, 5);
    }

    public BigDecimal minPeakRelativeHeight() {
        return decimal("min-peak-relative-height", "0.20", new BigDecimal("0.01"), BigDecimal.ONE);
    }

    public BigDecimal zoneEdgeRelativeHeight() {
        return decimal("zone-edge-relative-height", "0.25", new BigDecimal("0.01"), BigDecimal.ONE);
    }

    public int minValidBars() {
        return integer("min-valid-bars", 80, 20, lookbackBars());
    }

    public BigDecimal minTurnoverCoverage() {
        return decimal("min-turnover-coverage", "0.95", BigDecimal.ZERO, BigDecimal.ONE);
    }

    public BigDecimal rankingWeight() {
        return decimal("weight", "0.25", BigDecimal.ZERO, BigDecimal.ONE);
    }

    public ChipActivationMode activationMode() {
        try {
            return ChipActivationMode.valueOf(text("activation-mode", "ACTIVE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ChipActivationMode.ACTIVE;
        }
    }

    public BigDecimal singleSourceCoefficient() {
        return decimal("single-source-coefficient", "1.00", BigDecimal.ZERO, BigDecimal.ONE);
    }

    public BigDecimal maxAverageCostDeviation() {
        return decimal("max-average-cost-deviation", "0.03", BigDecimal.ZERO, BigDecimal.ONE);
    }

    public BigDecimal minCostBandOverlap() {
        return decimal("min-cost-band-overlap", "0.70", BigDecimal.ZERO, BigDecimal.ONE);
    }

    public BigDecimal maxWinnerRateDeviation() {
        return decimal("max-winner-rate-deviation", "0.10", BigDecimal.ZERO, BigDecimal.ONE);
    }

    public boolean tushareEnabled() {
        return bool("tushare.enabled", false);
    }

    public String tushareBaseUrl() {
        return text("tushare.base-url", "https://api.tushare.pro");
    }

    public String tushareToken() {
        return text("tushare.token", "");
    }

    public int tushareConnectTimeoutMs() {
        return integer("tushare.connect-timeout-ms", 1200, 200, 10000);
    }

    public int tushareReadTimeoutMs() {
        return integer("tushare.read-timeout-ms", 1800, 300, 15000);
    }

    public int tushareMaxConcurrency() {
        return integer("tushare.max-concurrency", 4, 1, 16);
    }

    private boolean bool(String suffix, boolean fallback) {
        String raw = environment.getProperty(PREFIX + suffix);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return false;
        }
        return fallback;
    }

    private int integer(String suffix, int fallback, int minimum, int maximum) {
        String raw = environment.getProperty(PREFIX + suffix);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= minimum && value <= maximum ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private BigDecimal decimal(String suffix, String fallbackText, BigDecimal minimum, BigDecimal maximum) {
        BigDecimal fallback = new BigDecimal(fallbackText);
        String raw = environment.getProperty(PREFIX + suffix);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0 ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String text(String suffix, String fallback) {
        String raw = environment.getProperty(PREFIX + suffix);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }
}

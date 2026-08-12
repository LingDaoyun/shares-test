package com.aistock.research.shortterm.validation;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class ShortTermValidationSettings {

    private static final String PREFIX = "research.short-term.validation.";
    private static final String DEFAULT_OUTCOME_CRON = "0 30 16 * * MON-FRI";
    private final Environment environment;
    private volatile String lastValidOutcomeCron = DEFAULT_OUTCOME_CRON;

    public ShortTermValidationSettings(Environment environment) {
        this.environment = environment;
    }

    public boolean enabled() {
        return bool("enabled", true);
    }

    public int batchSize() {
        return integer("batch-size", 100, 1, 1000);
    }

    public int minimumCohortSamples() {
        return integer("minimum-cohort-samples", 30, 5, 10000);
    }

    public String outcomeCron() {
        String value = text("outcome-cron", DEFAULT_OUTCOME_CRON);
        try {
            CronExpression.parse(value);
            lastValidOutcomeCron = value;
            return value;
        } catch (RuntimeException exception) {
            return lastValidOutcomeCron;
        }
    }

    public String zone() {
        return "Asia/Shanghai";
    }

    public ShortTermValidationCostAssumptions costAssumptions() {
        return new ShortTermValidationCostAssumptions(
                decimal("buy-commission-percent", "0.03"),
                decimal("sell-commission-percent", "0.03"),
                decimal("sell-stamp-duty-percent", "0.05"),
                decimal("buy-slippage-percent", "0.05"),
                decimal("sell-slippage-percent", "0.05")
        );
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

    private BigDecimal decimal(String suffix, String fallbackText) {
        String raw = environment.getProperty(PREFIX + suffix);
        if (raw == null || raw.isBlank()) {
            return new BigDecimal(fallbackText);
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            return value.signum() >= 0 && value.compareTo(new BigDecimal("5")) <= 0
                    ? value : new BigDecimal(fallbackText);
        } catch (NumberFormatException exception) {
            return new BigDecimal(fallbackText);
        }
    }

    private String text(String suffix, String fallback) {
        String raw = environment.getProperty(PREFIX + suffix);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }
}

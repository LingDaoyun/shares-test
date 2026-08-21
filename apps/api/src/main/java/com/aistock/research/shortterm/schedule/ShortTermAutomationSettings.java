package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.OvernightRuleSet;
import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

@Component
public class ShortTermAutomationSettings {

    private static final Logger log = LoggerFactory.getLogger(ShortTermAutomationSettings.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final Environment environment;

    public ShortTermAutomationSettings(Environment environment) {
        this.environment = environment;
    }

    public Duration freshness() {
        return Duration.ofSeconds(integer(
                "research.short-term.freshness-seconds", 180, 30, 3600));
    }

    public OvernightRuleSet overnightRules() {
        LocalTime entryStart = boundedEntryTime(
                "research.short-term.overnight.entry-start",
                TradingClockService.SHORT_TERM_ENTRY_START);
        LocalTime entryEnd = boundedEntryTime(
                "research.short-term.overnight.entry-end",
                TradingClockService.SHORT_TERM_ENTRY_END);
        if (entryStart.isAfter(entryEnd)) {
            warnFallback(
                    "research.short-term.overnight.entry-window",
                    entryStart + "-" + entryEnd,
                    TradingClockService.SHORT_TERM_ENTRY_START + "-" + TradingClockService.SHORT_TERM_ENTRY_END);
            entryStart = TradingClockService.SHORT_TERM_ENTRY_START;
            entryEnd = TradingClockService.SHORT_TERM_ENTRY_END;
        }
        return new OvernightRuleSet(
                entryStart,
                entryEnd,
                time("research.short-term.overnight.normal-exit-time", "14:50"),
                integer("research.short-term.overnight.max-holding-trading-days", 2, 1, 10),
                decimal("research.short-term.overnight.max-position-ratio", "0.3333",
                        new BigDecimal("0.01"), ONE),
                decimal("research.short-term.overnight.max-t2-position-ratio", "0.50",
                        new BigDecimal("0.01"), ONE),
                decimal("research.short-term.overnight.first-target-floor-percent", "2.5", ZERO,
                        new BigDecimal("30")),
                decimal("research.short-term.overnight.first-target-cap-percent", "4.0", ZERO,
                        new BigDecimal("30")),
                decimal("research.short-term.overnight.second-target-floor-percent", "4.5", ZERO,
                        new BigDecimal("30")),
                decimal("research.short-term.overnight.second-target-cap-percent", "7.0", ZERO,
                        new BigDecimal("30")),
                decimal("research.short-term.overnight.stop-floor-percent", "2.5", ZERO,
                        new BigDecimal("20")),
                decimal("research.short-term.overnight.stop-cap-percent", "4.5", ZERO,
                        new BigDecimal("20")),
                decimal("research.short-term.overnight.trailing-drawdown-percent", "2.0", ZERO,
                        new BigDecimal("20")));
    }

    private int integer(String key, int fallback, int minimum, int maximum) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Warning below includes the rejected refreshed value and fallback.
        }
        warnFallback(key, raw, fallback);
        return fallback;
    }

    private BigDecimal decimal(
            String key,
            String fallbackText,
            BigDecimal minimum,
            BigDecimal maximum
    ) {
        BigDecimal fallback = new BigDecimal(fallbackText);
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Warning below includes the rejected refreshed value and fallback.
        }
        warnFallback(key, raw, fallbackText);
        return fallback;
    }

    private LocalTime time(String key, String fallbackText) {
        String value = text(key, fallbackText);
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException exception) {
            warnFallback(key, value, fallbackText);
            return LocalTime.parse(fallbackText);
        }
    }

    private LocalTime boundedEntryTime(String key, LocalTime fallback) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            LocalTime value = LocalTime.parse(raw.trim());
            if (!value.isBefore(TradingClockService.SHORT_TERM_ENTRY_START)
                    && value.isBefore(TradingClockService.SHORT_TERM_ENTRY_EXCLUSIVE_END)) {
                return value;
            }
        } catch (DateTimeParseException ignored) {
            // Warning below includes the rejected refreshed value and fallback.
        }
        warnFallback(key, raw, fallback);
        return fallback;
    }

    private String text(String key, String fallback) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim();
    }

    private void warnFallback(String key, Object rejected, Object fallback) {
        log.warn("Invalid refreshed setting {}={}, using default {}", key, rejected, fallback);
    }

}

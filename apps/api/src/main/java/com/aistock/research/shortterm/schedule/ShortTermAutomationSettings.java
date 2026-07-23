package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.OvernightRuleSet;
import com.aistock.research.shortterm.ShortTermScanRequest;
import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ShortTermAutomationSettings {

    private static final Logger log = LoggerFactory.getLogger(ShortTermAutomationSettings.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final Environment environment;
    private final Map<String, String> lastValidCrons = new ConcurrentHashMap<>();

    public ShortTermAutomationSettings(Environment environment) {
        this.environment = environment;
    }

    public boolean enabled() {
        return bool("research.short-term.schedule.enabled", false);
    }

    public String zone() {
        String key = "research.short-term.schedule.zone";
        String fallback = "Asia/Shanghai";
        String value = text(key, fallback);
        try {
            ZoneId.of(value);
            return value;
        } catch (RuntimeException exception) {
            warnFallback(key, value, fallback);
            return fallback;
        }
    }

    public String preselectCron() {
        return cron("research.short-term.schedule.preselect-cron", "0 30 14 * * MON-FRI");
    }

    public String finalCron() {
        return cron("research.short-term.schedule.final-cron", "0 48 14 * * MON-FRI");
    }

    public String readinessCron() {
        return cron("research.short-term.schedule.readiness-cron", "0 54 14 * * MON-FRI");
    }

    public LocalTime finalDeadline() {
        return time("research.short-term.schedule.final-deadline", "14:53:59");
    }

    public Duration freshness() {
        return Duration.ofSeconds(integer(
                "research.short-term.schedule.freshness-seconds", 180, 30, 3600));
    }

    public ShortTermScanRequest scanRequest() {
        return new ShortTermScanRequest(
                integer("research.short-term.schedule.limit", 3, 1, 20),
                integer("research.short-term.schedule.scan-limit", 6000, 100, 10000),
                integer("research.short-term.schedule.kline-limit", 60, 20, 500),
                decimal("research.short-term.schedule.min-amount", "80000000", ZERO,
                        new BigDecimal("100000000000000")),
                decimal("research.short-term.schedule.max-pe", "100", ONE, new BigDecimal("1000")),
                decimal("research.short-term.schedule.max-pb", "15", new BigDecimal("0.1"), HUNDRED),
                decimal("research.short-term.schedule.min-volume-ratio", "1.15",
                        new BigDecimal("0.1"), new BigDecimal("10")),
                decimal("research.short-term.schedule.max-entry-rise", "4", ZERO, new BigDecimal("20")),
                decimal("research.short-term.schedule.max-distance-to-ma20", "8", ZERO, new BigDecimal("50")),
                decimal("research.short-term.schedule.min-financial-score", "58", ZERO, HUNDRED));
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

    private boolean bool(String key, boolean fallback) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        warnFallback(key, value, fallback);
        return fallback;
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

    private String cron(String key, String fallback) {
        String value = text(key, fallback);
        try {
            CronExpression.parse(value);
            lastValidCrons.put(key, value);
            return value;
        } catch (IllegalArgumentException exception) {
            String retained = lastValidCrons.getOrDefault(key, fallback);
            warnFallback(key, value, retained);
            return retained;
        }
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

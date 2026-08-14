package com.aistock.research.shortterm.schedule;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aistock.research.shortterm.OvernightRuleSet;
import com.aistock.research.shortterm.ShortTermScanRequest;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermAutomationSettingsTest {

    @Test
    void readsApprovedDefaults() {
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(new MockEnvironment());

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.zone()).isEqualTo("Asia/Shanghai");
        assertThat(settings.preselectCron()).isEqualTo("0 30 14 * * MON-FRI");
        assertThat(settings.finalCron()).isEqualTo("0 47 14 * * MON-FRI");
        assertThat(settings.readinessCron()).isEqualTo("50 49 14 * * MON-FRI");
        assertThat(settings.finalDeadline()).isEqualTo(LocalTime.of(14, 49, 40));
        assertThat(settings.freshness()).isEqualTo(Duration.ofSeconds(180));

        ShortTermScanRequest scan = settings.scanRequest();
        assertThat(scan.limit()).isEqualTo(8);
        assertThat(scan.scanLimit()).isEqualTo(6000);
        assertThat(scan.klineLimit()).isEqualTo(120);
        assertThat(scan.minAmount()).isEqualByComparingTo("80000000");
        assertThat(scan.minVolumeRatio()).isEqualByComparingTo("1.20");
        assertThat(scan.maxEntryRise()).isEqualByComparingTo("6.5");
        assertThat(scan.maxDistanceToMa20()).isEqualByComparingTo("8");
        assertThat(scan.minFinancialScore()).isEqualByComparingTo("55");

        OvernightRuleSet rules = settings.overnightRules();
        assertThat(rules.entryStart()).isEqualTo(LocalTime.of(14, 45));
        assertThat(rules.entryEnd()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_END);
        assertThat(rules.normalExitTime()).isEqualTo(LocalTime.of(14, 50));
        assertThat(rules.maxHoldingTradingDays()).isEqualTo(2);
        assertThat(rules.maxPositionRatio()).isEqualByComparingTo("0.3333");
        assertThat(rules.maxT2PositionRatio()).isEqualByComparingTo("0.50");
        assertThat(rules.firstTargetFloor()).isEqualByComparingTo("2.5");
        assertThat(rules.firstTargetCap()).isEqualByComparingTo("4.0");
        assertThat(rules.secondTargetFloor()).isEqualByComparingTo("4.5");
        assertThat(rules.secondTargetCap()).isEqualByComparingTo("7.0");
        assertThat(rules.stopFloor()).isEqualByComparingTo("2.5");
        assertThat(rules.stopCap()).isEqualByComparingTo("4.5");
        assertThat(rules.trailingDrawdownPercent()).isEqualByComparingTo("2.0");
    }

    @Test
    void refreshesEachReadFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.schedule.limit", "3");
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);

        assertThat(settings.scanRequest().limit()).isEqualTo(3);

        environment.setProperty("research.short-term.schedule.limit", "7");

        assertThat(settings.scanRequest().limit()).isEqualTo(7);
    }

    @Test
    void rejectsVolumeRatioThresholdsOutsideTheApprovedRange() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.schedule.min-volume-ratio", "0.8");
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);

        assertThat(settings.scanRequest().minVolumeRatio()).isEqualByComparingTo("1.20");

        environment.setProperty("research.short-term.schedule.min-volume-ratio", "3.3");

        assertThat(settings.scanRequest().minVolumeRatio()).isEqualByComparingTo("1.20");
    }

    @Test
    void invalidCronRefreshRetainsTheLastValidValueForThatTrigger() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.schedule.preselect-cron", "0 31 14 * * MON-FRI")
                .withProperty("research.short-term.schedule.final-cron", "0 49 14 * * MON-FRI");
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);

        assertThat(settings.preselectCron()).isEqualTo("0 31 14 * * MON-FRI");
        assertThat(settings.finalCron()).isEqualTo("0 49 14 * * MON-FRI");

        environment.setProperty("research.short-term.schedule.preselect-cron", "invalid-preselect");
        environment.setProperty("research.short-term.schedule.final-cron", "invalid-final");

        assertThat(settings.preselectCron()).isEqualTo("0 31 14 * * MON-FRI");
        assertThat(settings.finalCron()).isEqualTo("0 49 14 * * MON-FRI");
        assertThat(settings.readinessCron()).isEqualTo("50 49 14 * * MON-FRI");
    }

    @Test
    void rejectsNonShanghaiZoneAndDeduplicatesWarningUntilValueBecomesValid() {
        String key = "research.short-term.schedule.zone";
        MockEnvironment environment = new MockEnvironment().withProperty(key, "UTC");
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertThat(settings.zone()).isEqualTo("Asia/Shanghai");
            assertThat(settings.zone()).isEqualTo("Asia/Shanghai");
            assertThat(warningsFor(appender, key)).isEqualTo(1);

            environment.setProperty(key, "Asia/Shanghai");
            assertThat(settings.zone()).isEqualTo("Asia/Shanghai");
            environment.setProperty(key, "UTC");
            assertThat(settings.zone()).isEqualTo("Asia/Shanghai");

            assertThat(warningsFor(appender, key)).isEqualTo(2);
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void deduplicatesInvalidCronWarningAndAllowsOneWarningAfterValidReset() {
        String key = "research.short-term.schedule.final-cron";
        MockEnvironment environment = new MockEnvironment()
                .withProperty(key, "0 49 14 * * MON-FRI");
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);
        assertThat(settings.finalCron()).isEqualTo("0 49 14 * * MON-FRI");
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            environment.setProperty(key, "invalid-final");
            assertThat(settings.finalCron()).isEqualTo("0 49 14 * * MON-FRI");
            assertThat(settings.finalCron()).isEqualTo("0 49 14 * * MON-FRI");
            assertThat(warningsFor(appender, key)).isEqualTo(1);

            environment.setProperty(key, "0 50 14 * * MON-FRI");
            assertThat(settings.finalCron()).isEqualTo("0 50 14 * * MON-FRI");
            environment.setProperty(key, "invalid-final");
            assertThat(settings.finalCron()).isEqualTo("0 50 14 * * MON-FRI");

            assertThat(warningsFor(appender, key)).isEqualTo(2);
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void invalidOrOutOfRangeRefreshedValuesFallBackWithoutThrowing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.schedule.enabled", "not-a-boolean")
                .withProperty("research.short-term.schedule.zone", "Mars/Phobos")
                .withProperty("research.short-term.schedule.final-cron", "not a cron")
                .withProperty("research.short-term.schedule.final-deadline", "25:99")
                .withProperty("research.short-term.schedule.freshness-seconds", "-1")
                .withProperty("research.short-term.schedule.limit", "0")
                .withProperty("research.short-term.schedule.min-volume-ratio", "NaN")
                .withProperty("research.short-term.overnight.entry-start", "bad-time")
                .withProperty("research.short-term.overnight.entry-end", "15:20")
                .withProperty("research.short-term.overnight.max-holding-trading-days", "0")
                .withProperty("research.short-term.overnight.max-position-ratio", "1.5");

        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.zone()).isEqualTo("Asia/Shanghai");
        assertThat(settings.finalCron()).isEqualTo("0 47 14 * * MON-FRI");
        assertThat(settings.finalDeadline()).isEqualTo(LocalTime.of(14, 49, 40));
        assertThat(settings.freshness()).isEqualTo(Duration.ofSeconds(180));
        assertThat(settings.scanRequest().limit()).isEqualTo(8);
        assertThat(settings.scanRequest().minVolumeRatio()).isEqualByComparingTo("1.20");
        assertThat(settings.overnightRules().entryStart()).isEqualTo(LocalTime.of(14, 45));
        assertThat(settings.overnightRules().entryEnd()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_END);
        assertThat(settings.overnightRules().maxHoldingTradingDays()).isEqualTo(2);
        assertThat(settings.overnightRules().maxPositionRatio())
                .isEqualByComparingTo(new BigDecimal("0.3333"));
    }

    @Test
    void boundsEntryOverridesToExecutableWindowAndRejectsInvertedRange() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.overnight.entry-start", "14:46")
                .withProperty("research.short-term.overnight.entry-end", "14:55");
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);

        assertThat(settings.overnightRules().entryStart()).isEqualTo(LocalTime.of(14, 46));
        assertThat(settings.overnightRules().entryEnd()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_END);

        environment.setProperty("research.short-term.overnight.entry-start", "14:56:59");
        environment.setProperty("research.short-term.overnight.entry-end", "14:56:59.999999999");

        assertThat(settings.overnightRules().entryStart()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_START);
        assertThat(settings.overnightRules().entryEnd()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_END);

        environment.setProperty("research.short-term.overnight.entry-start", "14:55");
        environment.setProperty("research.short-term.overnight.entry-end", "14:50");

        assertThat(settings.overnightRules().entryStart()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_START);
        assertThat(settings.overnightRules().entryEnd()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_END);
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ShortTermAutomationSettings.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ShortTermAutomationSettings.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private long warningsFor(ListAppender<ILoggingEvent> appender, String key) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(key))
                .count();
    }
}

package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.OvernightRuleSet;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermAutomationSettingsTest {

    @Test
    void readsManualScanDefaults() {
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(new MockEnvironment());

        assertThat(settings.freshness()).isEqualTo(Duration.ofSeconds(180));

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
    void refreshesManualSettingsFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.freshness-seconds", "240")
                .withProperty("research.short-term.overnight.entry-start", "14:46");
        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);

        assertThat(settings.freshness()).isEqualTo(Duration.ofSeconds(240));
        assertThat(settings.overnightRules().entryStart()).isEqualTo(LocalTime.of(14, 46));

        environment.setProperty("research.short-term.freshness-seconds", "120");
        environment.setProperty("research.short-term.overnight.entry-start", "14:47");

        assertThat(settings.freshness()).isEqualTo(Duration.ofSeconds(120));
        assertThat(settings.overnightRules().entryStart()).isEqualTo(LocalTime.of(14, 47));
    }

    @Test
    void invalidManualSettingsFallBackWithoutThrowing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.freshness-seconds", "-1")
                .withProperty("research.short-term.overnight.entry-start", "bad-time")
                .withProperty("research.short-term.overnight.entry-end", "15:20")
                .withProperty("research.short-term.overnight.max-holding-trading-days", "0")
                .withProperty("research.short-term.overnight.max-position-ratio", "1.5");

        ShortTermAutomationSettings settings = new ShortTermAutomationSettings(environment);

        assertThat(settings.freshness()).isEqualTo(Duration.ofSeconds(180));
        assertThat(settings.overnightRules().entryStart()).isEqualTo(TradingClockService.SHORT_TERM_ENTRY_START);
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
}

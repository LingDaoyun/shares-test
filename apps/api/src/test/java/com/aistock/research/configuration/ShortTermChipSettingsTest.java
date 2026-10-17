package com.aistock.research.configuration;

import com.aistock.research.shortterm.chip.ChipActivationMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermChipSettingsTest {

    @Test
    void usesActiveLocalDistributionDefaults() {
        ShortTermChipSettings settings = new ShortTermChipSettings(new MockEnvironment());

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.lookbackBars()).isEqualTo(120);
        assertThat(settings.priceBuckets()).isEqualTo(150);
        assertThat(settings.displayBuckets()).isEqualTo(60);
        assertThat(settings.maxConcentrationZones()).isEqualTo(3);
        assertThat(settings.minPeakRelativeHeight()).isEqualByComparingTo("0.20");
        assertThat(settings.zoneEdgeRelativeHeight()).isEqualByComparingTo("0.25");
        assertThat(settings.activationMode()).isEqualTo(ChipActivationMode.ACTIVE);
        assertThat(settings.nearOverheadMaxPenalty()).isEqualByComparingTo("10");
        assertThat(settings.singleSourceCoefficient()).isEqualByComparingTo("1.00");
        assertThat(settings.tushareEnabled()).isFalse();
        assertThat(settings.tushareToken()).isEmpty();
    }

    @Test
    void fallsBackToShadowForUnknownActivationMode() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.chip.activation-mode", "CERTAIN_BUY");

        ShortTermChipSettings settings = new ShortTermChipSettings(environment);

        assertThat(settings.activationMode()).isEqualTo(ChipActivationMode.SHADOW);
    }

    @Test
    void allowsOverridingAndDisablingNearOverheadPenalty() {
        ShortTermChipSettings overridden = new ShortTermChipSettings(new MockEnvironment()
                .withProperty("research.short-term.chip.near-overhead-max-penalty", "18.5"));
        ShortTermChipSettings disabled = new ShortTermChipSettings(new MockEnvironment()
                .withProperty("research.short-term.chip.near-overhead-max-penalty", "0"));

        assertThat(overridden.nearOverheadMaxPenalty()).isEqualByComparingTo("18.5");
        assertThat(disabled.nearOverheadMaxPenalty()).isEqualByComparingTo("0");
    }
}

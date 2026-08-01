package com.aistock.research.configuration;

import com.aistock.research.shortterm.chip.ChipActivationMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermChipSettingsTest {

    @Test
    void usesSafeShadowDefaults() {
        ShortTermChipSettings settings = new ShortTermChipSettings(new MockEnvironment());

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.lookbackBars()).isEqualTo(120);
        assertThat(settings.priceBuckets()).isEqualTo(150);
        assertThat(settings.activationMode()).isEqualTo(ChipActivationMode.SHADOW);
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
}

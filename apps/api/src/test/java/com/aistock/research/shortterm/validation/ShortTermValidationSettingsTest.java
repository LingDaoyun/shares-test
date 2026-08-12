package com.aistock.research.shortterm.validation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermValidationSettingsTest {

    @Test
    void readsApprovedDefaultsAndCostAssumptions() {
        ShortTermValidationSettings settings = new ShortTermValidationSettings(new MockEnvironment());

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.outcomeCron()).isEqualTo("0 30 16 * * MON-FRI");
        assertThat(settings.batchSize()).isEqualTo(100);
        assertThat(settings.minimumCohortSamples()).isEqualTo(30);
        assertThat(settings.costAssumptions().buyCommissionPercent()).isEqualByComparingTo("0.03");
        assertThat(settings.costAssumptions().sellCommissionPercent()).isEqualByComparingTo("0.03");
        assertThat(settings.costAssumptions().sellStampDutyPercent()).isEqualByComparingTo("0.05");
        assertThat(settings.costAssumptions().buySlippagePercent()).isEqualByComparingTo("0.05");
        assertThat(settings.costAssumptions().sellSlippagePercent()).isEqualByComparingTo("0.05");
    }

    @Test
    void refreshesFromEnvironmentAndRetainsLastValidCron() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("research.short-term.validation.outcome-cron", "0 20 16 * * MON-FRI")
                .withProperty("research.short-term.validation.minimum-cohort-samples", "40");
        ShortTermValidationSettings settings = new ShortTermValidationSettings(environment);

        assertThat(settings.outcomeCron()).isEqualTo("0 20 16 * * MON-FRI");
        assertThat(settings.minimumCohortSamples()).isEqualTo(40);

        environment.setProperty("research.short-term.validation.outcome-cron", "invalid-cron");
        environment.setProperty("research.short-term.validation.minimum-cohort-samples", "60");

        assertThat(settings.outcomeCron()).isEqualTo("0 20 16 * * MON-FRI");
        assertThat(settings.minimumCohortSamples()).isEqualTo(60);
    }
}

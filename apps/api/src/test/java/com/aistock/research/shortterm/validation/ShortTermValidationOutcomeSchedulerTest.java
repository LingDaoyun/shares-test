package com.aistock.research.shortterm.validation;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermValidationOutcomeSchedulerTest {

    private final ShortTermOutcomeMaturationService outcomes = mock(ShortTermOutcomeMaturationService.class);
    private final ShortTermValidationSettings settings = mock(ShortTermValidationSettings.class);

    @Test
    void registersOneRefreshableOutcomeTask() {
        when(settings.outcomeCron()).thenReturn("0 30 16 * * MON-FRI");
        ShortTermValidationOutcomeScheduler scheduler =
                new ShortTermValidationOutcomeScheduler(outcomes, settings);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).hasSize(1);
    }

    @Test
    void skipsRefreshWhenValidationIsDisabled() {
        when(settings.enabled()).thenReturn(false);
        ShortTermValidationOutcomeScheduler scheduler =
                new ShortTermValidationOutcomeScheduler(outcomes, settings);

        scheduler.refreshPending();

        verify(outcomes, never()).refreshPending();
    }

    @Test
    void refreshesPendingOutcomesWhenEnabled() {
        when(settings.enabled()).thenReturn(true);
        when(outcomes.refreshPending()).thenReturn(ShortTermOutcomeRefreshResult.empty());
        ShortTermValidationOutcomeScheduler scheduler =
                new ShortTermValidationOutcomeScheduler(outcomes, settings);

        scheduler.refreshPending();

        verify(outcomes).refreshPending();
    }
}

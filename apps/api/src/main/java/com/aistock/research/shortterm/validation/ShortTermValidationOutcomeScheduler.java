package com.aistock.research.shortterm.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class ShortTermValidationOutcomeScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ShortTermValidationOutcomeScheduler.class);

    private final ShortTermOutcomeMaturationService outcomeService;
    private final ShortTermValidationSettings settings;

    public ShortTermValidationOutcomeScheduler(
            ShortTermOutcomeMaturationService outcomeService,
            ShortTermValidationSettings settings
    ) {
        this.outcomeService = outcomeService;
        this.settings = settings;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(
                this::refreshPending,
                context -> new CronTrigger(
                        settings.outcomeCron(), ZoneId.of(settings.zone()))
                        .nextExecution(context)
        );
    }

    public void refreshPending() {
        if (!settings.enabled()) {
            return;
        }
        try {
            ShortTermOutcomeRefreshResult result = outcomeService.refreshPending();
            if (result.observationCount() > 0) {
                log.info(
                        "Short-term validation outcomes refreshed, observations={}, matured={}, unavailable={}, retryable={}, pending={}",
                        result.observationCount(),
                        result.maturedCount(),
                        result.unavailableCount(),
                        result.retryableCount(),
                        result.pendingCount()
                );
            }
        } catch (RuntimeException exception) {
            log.warn("Short-term validation outcome refresh failed and will retry", exception);
        }
    }
}

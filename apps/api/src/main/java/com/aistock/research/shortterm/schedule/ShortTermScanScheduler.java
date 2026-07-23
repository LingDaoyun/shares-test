package com.aistock.research.shortterm.schedule;

import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.FINAL;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.PRESELECT;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.READINESS_GUARD;

@Component
public class ShortTermScanScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ShortTermScanScheduler.class);
    private static final Map<ShortTermSnapshotStage, String> DEFAULT_CRONS = Map.of(
            PRESELECT, "0 30 14 * * MON-FRI",
            FINAL, "0 48 14 * * MON-FRI",
            READINESS_GUARD, "0 54 14 * * MON-FRI"
    );

    private final ShortTermScheduledScanService scheduledScanService;
    private final ShortTermAutomationSettings settings;
    private final Map<ShortTermSnapshotStage, RefreshableTrigger> triggers =
            new EnumMap<>(ShortTermSnapshotStage.class);

    public ShortTermScanScheduler(
            ShortTermScheduledScanService scheduledScanService,
            ShortTermAutomationSettings settings
    ) {
        this.scheduledScanService = scheduledScanService;
        this.settings = settings;
        triggers.put(PRESELECT, trigger(PRESELECT, settings::preselectCron));
        triggers.put(FINAL, trigger(FINAL, settings::finalCron));
        triggers.put(READINESS_GUARD, trigger(READINESS_GUARD, settings::readinessCron));
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        register(taskRegistrar, PRESELECT);
        register(taskRegistrar, FINAL);
        register(taskRegistrar, READINESS_GUARD);
    }

    private void register(ScheduledTaskRegistrar registrar, ShortTermSnapshotStage stage) {
        registrar.addTriggerTask(
                () -> scheduledScanService.submit(stage),
                triggers.get(stage)
        );
    }

    private RefreshableTrigger trigger(
            ShortTermSnapshotStage stage,
            Supplier<String> cronSupplier
    ) {
        CronTrigger fallback = new CronTrigger(
                DEFAULT_CRONS.get(stage), TradingClockService.CHINA_MARKET_ZONE);
        return new RefreshableTrigger(stage, cronSupplier, fallback);
    }

    private final class RefreshableTrigger implements Trigger {

        private final ShortTermSnapshotStage stage;
        private final Supplier<String> cronSupplier;
        private volatile CronTrigger lastValid;
        private volatile String lastWarningKey;

        private RefreshableTrigger(
                ShortTermSnapshotStage stage,
                Supplier<String> cronSupplier,
                CronTrigger fallback
        ) {
            this.stage = stage;
            this.cronSupplier = cronSupplier;
            this.lastValid = fallback;
        }

        @Override
        public Instant nextExecution(TriggerContext triggerContext) {
            String cron = cronSupplier.get();
            String zone = settings.zone();
            try {
                CronTrigger refreshed = new CronTrigger(
                        cron, TradingClockService.CHINA_MARKET_ZONE);
                lastValid = refreshed;
                lastWarningKey = null;
            } catch (RuntimeException exception) {
                String warningKey = cron + "@" + zone;
                if (!warningKey.equals(lastWarningKey)) {
                    log.warn(
                            "Invalid refreshed short-term cron retained last valid trigger, stage={}, cron={}, zone={}",
                            stage, cron, zone);
                    lastWarningKey = warningKey;
                }
            }
            return lastValid.nextExecution(triggerContext);
        }
    }
}

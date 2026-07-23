package com.aistock.research.shortterm.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.FINAL;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.PRESELECT;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.READINESS_GUARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermScanSchedulerTest {

    @Test
    void registersThreeIndependentRefreshableTriggers() {
        ShortTermScheduledScanService service = mock(ShortTermScheduledScanService.class);
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        when(settings.zone()).thenReturn("Asia/Shanghai");
        when(settings.preselectCron()).thenReturn("0 30 14 * * MON-FRI");
        when(settings.finalCron()).thenReturn("0 48 14 * * MON-FRI");
        when(settings.readinessCron()).thenReturn("0 54 14 * * MON-FRI");
        ShortTermScanScheduler scheduler = new ShortTermScanScheduler(service, settings);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduler.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).hasSize(3);
        registrar.getTriggerTaskList().get(0).getRunnable().run();
        registrar.getTriggerTaskList().get(1).getRunnable().run();
        registrar.getTriggerTaskList().get(2).getRunnable().run();
        verify(service).submit(PRESELECT);
        verify(service).submit(FINAL);
        verify(service).submit(READINESS_GUARD);
    }

    @Test
    void invalidRefreshRetainsEachTriggersLastValidCronAndDoesNotStopSiblings() {
        ShortTermScheduledScanService service = mock(ShortTermScheduledScanService.class);
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        when(settings.zone()).thenReturn("Asia/Shanghai");
        when(settings.preselectCron()).thenReturn("0 30 14 * * MON-FRI", "not-a-cron");
        when(settings.finalCron()).thenReturn("0 48 14 * * MON-FRI", "also-invalid");
        when(settings.readinessCron()).thenReturn("0 54 14 * * MON-FRI", "0 55 14 * * MON-FRI");
        ShortTermScanScheduler scheduler = new ShortTermScanScheduler(service, settings);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        List<Trigger> triggers = registrar.getTriggerTaskList().stream()
                .map(task -> task.getTrigger())
                .toList();
        Instant base = Instant.parse("2026-07-22T06:00:00Z");

        List<Instant> first = triggers.stream().map(trigger -> trigger.nextExecution(context(base))).toList();
        List<Instant> refreshed = triggers.stream().map(trigger -> trigger.nextExecution(context(base))).toList();

        assertThat(first).containsExactly(
                Instant.parse("2026-07-22T06:30:00Z"),
                Instant.parse("2026-07-22T06:48:00Z"),
                Instant.parse("2026-07-22T06:54:00Z"));
        assertThat(refreshed).containsExactly(
                first.get(0),
                first.get(1),
                Instant.parse("2026-07-22T06:55:00Z"));
    }

    @Test
    void executorIsSingleWorkerBoundedAndIndependentFromManualQueue() throws Exception {
        ExecutorService executor = new ShortTermScheduledExecutorConfig().shortTermScheduledExecutor();
        try {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
            assertThat(pool.getCorePoolSize()).isOne();
            assertThat(pool.getMaximumPoolSize()).isOne();
            assertThat(pool.getQueue().remainingCapacity()).isOne();

            CountDownLatch ran = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> threadName =
                    new java.util.concurrent.atomic.AtomicReference<>();
            executor.submit(() -> {
                threadName.set(Thread.currentThread().getName());
                ran.countDown();
            });

            assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("short-term-scheduled-");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TriggerContext context(Instant base) {
        SimpleTriggerContext context = new SimpleTriggerContext();
        context.update(base.minus(Duration.ofMinutes(1)), base.minus(Duration.ofMinutes(1)), base);
        return context;
    }
}

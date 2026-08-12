package com.aistock.research.shortterm;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.schedule.ShortTermFinalResultGate;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.shortterm.validation.ShortTermObservationService;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.trading.TradingSessionSnapshot;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermScanJobServiceTest {

    @Test
    void shouldUseConfiguredScanTimeoutFromSpringProperties() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("research.short-term.scan-job-timeout=PT15M").applyTo(context);
        context.registerBean(ShortTermService.class, () -> mock(ShortTermService.class));
        context.registerBean(ResearchHistoryService.class, () -> mock(ResearchHistoryService.class));
        context.registerBean(TradingClockService.class, () -> mock(TradingClockService.class));
        context.registerBean(ShortTermFinalResultGate.class, () -> mock(ShortTermFinalResultGate.class));
        context.registerBean(ShortTermObservationService.class, () -> mock(ShortTermObservationService.class));
        context.register(ShortTermScanJobService.class);

        try {
            context.refresh();

            assertThat(configuredTimeout(context.getBean(ShortTermScanJobService.class)))
                    .isEqualTo(Duration.ofMinutes(15));
        } finally {
            context.close();
        }
    }

    @Test
    void shouldRunScanJobAndExposeSucceededReport() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        ResearchHistoryService researchHistoryService = mock(ResearchHistoryService.class);
        ShortTermReport report = sampleReport();
        ShortTermScanJobService service = service(shortTermService, researchHistoryService);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            ShortTermScanRequest request = new ShortTermScanRequest(
                    3,
                    120,
                    20,
                    new BigDecimal("100000000"),
                    new BigDecimal("35"),
                    new BigDecimal("4"),
                    new BigDecimal("1.20"),
                    new BigDecimal("3.50"),
                    new BigDecimal("7.00"),
                    new BigDecimal("60"),
                    true
            );
            when(shortTermService.report(eq(request))).thenAnswer(invocation -> {
                running.countDown();
                release.await(10, TimeUnit.SECONDS);
                return report;
            });
            ShortTermScanJobStatus started = service.start(request);

            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(started.status()).isEqualTo("RUNNING");
            assertThat(started.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.RUNNING);
            release.countDown();
            ShortTermScanJobStatus finished = awaitFinished(service, started.jobId());

            assertThat(finished.status()).isEqualTo("SUCCEEDED");
            assertThat(finished.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.DATA_BLOCKED);
            assertThat(finished.strategyVersion()).isEqualTo("short-term-right-side-v4-transparent-ranking");
            assertThat(finished.report()).isSameAs(report);
            assertThat(finished.message()).contains("不可执行");
            verify(shortTermService).report(eq(request));
            verify(researchHistoryService).recordShortTermReport(report);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void shouldExposeFailedStatusWhenRealtimeScanFails() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        when(shortTermService.report(eq(ShortTermScanRequest.empty())))
                .thenThrow(new IllegalStateException("短线右侧实时行情加载失败"));
        ShortTermScanJobService service = service(shortTermService, null);

        try {
            ShortTermScanJobStatus started = service.start(ShortTermScanRequest.empty());
            ShortTermScanJobStatus finished = awaitFinished(service, started.jobId());

            assertThat(finished.status()).isEqualTo("FAILED");
            assertThat(finished.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FAILED);
            assertThat(finished.report()).isNull();
            assertThat(finished.message()).contains("实时行情加载失败");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldArchiveManualObservationWithItsActualResultGateStatus() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        ResearchHistoryService historyService = mock(ResearchHistoryService.class);
        ShortTermObservationService observationService = mock(ShortTermObservationService.class);
        TradingClockService tradingClock = mock(TradingClockService.class);
        ShortTermFinalResultGate gate = mock(ShortTermFinalResultGate.class);
        ShortTermReport report = sampleReport();
        Instant now = Instant.parse("2026-07-23T06:49:00Z");
        when(tradingClock.currentMarketDate()).thenReturn(LocalDate.parse("2026-07-23"));
        when(shortTermService.report(eq(ShortTermScanRequest.empty()))).thenReturn(report);
        when(gate.evaluateManual(eq(report), any())).thenReturn(new ShortTermFinalResultGate.Result(
                ShortTermSnapshotStatus.FINAL_READY, "手动终选已就绪", List.of()));
        ShortTermScanJobService service = new ShortTermScanJobService(
                shortTermService, historyService, tradingClock, gate, observationService,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));

        try {
            ShortTermScanJobStatus started = service.start(ShortTermScanRequest.empty());
            awaitFinished(service, started.jobId());

            verify(observationService).captureManual(
                    eq(started.jobId()), eq(report), eq(ShortTermSnapshotStatus.FINAL_READY),
                    eq(List.of()), eq(now));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldRejectNewScanWhenWorkersAndBoundedQueueAreFull() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);
        when(shortTermService.report(eq(ShortTermScanRequest.empty())))
                .thenAnswer(invocation -> {
                    running.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return sampleReport();
                });
        ShortTermScanJobService service = service(shortTermService, null);

        try {
            for (int index = 0; index < 6; index++) {
                service.start(ShortTermScanRequest.empty());
            }
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.start(ShortTermScanRequest.empty()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("任务繁忙");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void shouldFailRunningScanWhenItExceedsTimeout() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        CountDownLatch release = new CountDownLatch(1);
        when(shortTermService.report(eq(ShortTermScanRequest.empty())))
                .thenAnswer(invocation -> {
                    release.await(10, TimeUnit.SECONDS);
                    return sampleReport();
                });
        ShortTermScanJobService service = service(shortTermService, null, Duration.ofMillis(50));

        try {
            ShortTermScanJobStatus started = service.start(ShortTermScanRequest.empty());
            ShortTermScanJobStatus finished = awaitFinished(service, started.jobId());

            assertThat(finished.status()).isEqualTo("FAILED");
            assertThat(finished.resultStatus()).isEqualTo(ShortTermSnapshotStatus.FAILED);
            assertThat(finished.message()).contains("超时");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    private ShortTermScanJobStatus awaitFinished(ShortTermScanJobService service, String jobId) throws InterruptedException {
        for (int index = 0; index < 40; index++) {
            ShortTermScanJobStatus status = service.get(jobId);
            if (!"RUNNING".equals(status.status())) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("scan job did not finish in time");
    }

    private Duration configuredTimeout(ShortTermScanJobService service) throws Exception {
        Field field = ShortTermScanJobService.class.getDeclaredField("scanTimeout");
        field.setAccessible(true);
        return (Duration) field.get(service);
    }

    private ShortTermScanJobService service(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService
    ) {
        return service(shortTermService, researchHistoryService, Duration.ofMinutes(5));
    }

    private ShortTermScanJobService service(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService,
            Duration timeout
    ) {
        Instant now = Instant.parse("2026-07-23T06:52:00Z");
        TradingClockService tradingClock = mock(TradingClockService.class);
        when(tradingClock.currentMarketDate()).thenReturn(LocalDate.parse("2026-07-23"));
        ShortTermFinalResultGate gate = mock(ShortTermFinalResultGate.class);
        when(gate.evaluateManual(any(), any())).thenReturn(new ShortTermFinalResultGate.Result(
                ShortTermSnapshotStatus.DATA_BLOCKED,
                "手动扫描不在当日尾盘决策窗口，结果不可执行",
                List.of("MANUAL_OUTSIDE_DECISION_WINDOW")
        ));
        return new ShortTermScanJobService(
                shortTermService,
                researchHistoryService,
                tradingClock,
                gate,
                Clock.fixed(now, ZoneOffset.UTC),
                timeout
        );
    }

    private ShortTermReport sampleReport() {
        return new ShortTermReport(
                "A 股短线右侧启动池",
                10,
                3,
                2,
                0,
                "测试",
                new TradingSessionSnapshot(
                        "CLOSED",
                        "非交易时段",
                        false,
                        false,
                        false,
                        "休市",
                        List.of("测试规则"),
                        List.of()
                ),
                List.of("测试方法"),
                new ShortTermRuleSet(
                        120,
                        20,
                        new BigDecimal("100000000"),
                        new BigDecimal("35"),
                        new BigDecimal("4"),
                        new BigDecimal("1.20"),
                        new BigDecimal("3.50"),
                        new BigDecimal("7.00"),
                        new BigDecimal("60")
                ),
                new ShortTermWeightProfile(
                        new BigDecimal("0.10"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.25"),
                        new BigDecimal("0.35"),
                        new BigDecimal("0.40"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.15"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.05")
                ),
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }
}

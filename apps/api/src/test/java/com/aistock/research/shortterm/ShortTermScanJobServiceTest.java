package com.aistock.research.shortterm;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.schedule.ShortTermFinalResultGate;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.trading.TradingSessionSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
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
    void shouldRunScanJobAndExposeSucceededReport() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        ResearchHistoryService researchHistoryService = mock(ResearchHistoryService.class);
        ShortTermReport report = sampleReport();
        when(shortTermService.report(
                eq(3),
                eq(120),
                eq(20),
                eq(new BigDecimal("100000000")),
                eq(new BigDecimal("35")),
                eq(new BigDecimal("4")),
                eq(new BigDecimal("1.20")),
                eq(new BigDecimal("3.50")),
                eq(new BigDecimal("7.00")),
                eq(new BigDecimal("60"))
        )).thenReturn(report);
        ShortTermScanJobService service = service(shortTermService, researchHistoryService);

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
                    new BigDecimal("60")
            );
            ShortTermScanJobStatus started = service.start(request);

            assertThat(started.status()).isEqualTo("RUNNING");
            assertThat(started.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.RUNNING);
            ShortTermScanJobStatus finished = awaitFinished(service, started.jobId());

            assertThat(finished.status()).isEqualTo("SUCCEEDED");
            assertThat(finished.resultStatus()).isEqualTo(
                    com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.DATA_BLOCKED);
            assertThat(finished.strategyVersion()).isEqualTo("short-term-right-side-v3-chip-verified");
            assertThat(finished.report()).isSameAs(report);
            assertThat(finished.message()).contains("不可执行");
            verify(shortTermService).report(
                    eq(3),
                    eq(120),
                    eq(20),
                    eq(new BigDecimal("100000000")),
                    eq(new BigDecimal("35")),
                    eq(new BigDecimal("4")),
                    eq(new BigDecimal("1.20")),
                    eq(new BigDecimal("3.50")),
                    eq(new BigDecimal("7.00")),
                    eq(new BigDecimal("60"))
            );
            verify(researchHistoryService).recordShortTermReport(report);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldExposeFailedStatusWhenRealtimeScanFails() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        when(shortTermService.report(null, null, null, null, null, null, null, null, null, null))
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
    void shouldRejectNewScanWhenWorkersAndBoundedQueueAreFull() throws Exception {
        ShortTermService shortTermService = mock(ShortTermService.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);
        when(shortTermService.report(null, null, null, null, null, null, null, null, null, null))
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

    private ShortTermScanJobService service(
            ShortTermService shortTermService,
            ResearchHistoryService researchHistoryService
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
                Clock.fixed(now, ZoneOffset.UTC)
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

package com.aistock.research.shortterm;

import com.aistock.research.shortterm.schedule.ShortTermAutomationSettings;
import com.aistock.research.shortterm.schedule.ShortTermScanScheduler;
import com.aistock.research.shortterm.schedule.ShortTermScheduledExecutorConfig;
import com.aistock.research.shortterm.schedule.ShortTermScheduledScanService;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshot;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshotController;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshotStore;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStage;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermScheduledSnapshotControllerTest {

    @Test
    void scheduledEndpointAndAutomationAreSpringManaged() {
        assertThat(ShortTermScheduledSnapshotController.class.isAnnotationPresent(RestController.class)).isTrue();
        assertThat(ShortTermScanScheduler.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(ShortTermScheduledScanService.class.isAnnotationPresent(Service.class)).isTrue();
        assertThat(ShortTermScheduledExecutorConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
    }

    @Test
    void returnsWaitingSnapshotBeforeTheFirstScheduledRun() {
        LocalDate date = LocalDate.of(2026, 7, 23);
        TradingClockService tradingClock = mock(TradingClockService.class);
        ShortTermScheduledSnapshotStore store = mock(ShortTermScheduledSnapshotStore.class);
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
        when(tradingClock.currentMarketDate()).thenReturn(date);
        when(store.latest(date, ShortTermSnapshotStage.FINAL)).thenReturn(Optional.empty());
        when(settings.preselectCron()).thenReturn("0 30 14 * * MON-FRI");
        ShortTermScheduledSnapshotController controller = new ShortTermScheduledSnapshotController(
                tradingClock, store, settings, attestations);

        ShortTermScheduledSnapshot snapshot = controller.latest();

        assertThat(snapshot.status()).isEqualTo(ShortTermSnapshotStatus.RUNNING);
        assertThat(snapshot.message()).isEqualTo("等待 0 30 14 * * MON-FRI 自动预选");
        assertThat(snapshot.report()).isNull();
    }

    @Test
    void attestsOnlyAnExecutableScheduledFinalReport() {
        LocalDate date = LocalDate.of(2026, 7, 23);
        Instant completedAt = Instant.parse("2026-07-23T06:49:30Z");
        TradingClockService tradingClock = mock(TradingClockService.class);
        ShortTermScheduledSnapshotStore store = mock(ShortTermScheduledSnapshotStore.class);
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
        ShortTermReport raw = mock(ShortTermReport.class);
        ShortTermReport attested = mock(ShortTermReport.class);
        ShortTermScheduledSnapshot persisted = new ShortTermScheduledSnapshot(
                date + ":FINAL:rules-v4", date, ShortTermSnapshotStage.FINAL,
                ShortTermSnapshotStatus.FINAL_READY, 1, "rules-v4", "{}", completedAt,
                completedAt.minusSeconds(30), completedAt, "已就绪", List.of(), raw,
                "a".repeat(64), completedAt);
        when(tradingClock.currentMarketDate()).thenReturn(date);
        when(store.latest(date, ShortTermSnapshotStage.FINAL)).thenReturn(Optional.of(persisted));
        when(settings.finalDeadline()).thenReturn(LocalTime.of(14, 49, 40));
        when(attestations.attest(raw)).thenReturn(attested);
        ShortTermScheduledSnapshotController controller = new ShortTermScheduledSnapshotController(
                tradingClock, store, settings, attestations);

        ShortTermScheduledSnapshot response = controller.latest();

        assertThat(response.report()).isSameAs(attested);
        verify(attestations).attest(raw);
    }

    @Test
    void failsClosedALegacyFinalReadyBeforeAttestationOrApiDisclosure() {
        LocalDate date = LocalDate.of(2026, 7, 23);
        Instant completedAt = Instant.parse("2026-07-23T06:49:30Z");
        Instant deadline = Instant.parse("2026-07-23T06:49:40Z");
        TradingClockService tradingClock = mock(TradingClockService.class);
        ShortTermScheduledSnapshotStore store = mock(ShortTermScheduledSnapshotStore.class);
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
        ShortTermReport raw = mock(ShortTermReport.class);
        ShortTermScheduledSnapshot legacy = new ShortTermScheduledSnapshot(
                date + ":FINAL:legacy", date, ShortTermSnapshotStage.FINAL,
                ShortTermSnapshotStatus.FINAL_READY, 1, "legacy", "{}", completedAt,
                completedAt.minusSeconds(30), completedAt, "旧版本终选", List.of(), raw);
        ShortTermScheduledSnapshot blocked = new ShortTermScheduledSnapshot(
                legacy.snapshotKey(), date, ShortTermSnapshotStage.FINAL,
                ShortTermSnapshotStatus.DATA_BLOCKED, 1, "legacy", "{}", completedAt,
                completedAt.minusSeconds(30), completedAt.plusSeconds(10),
                "尾盘终选缺少有效截止认证证明",
                List.of("FINAL_CERTIFICATION_PROOF_INVALID"), raw);
        when(tradingClock.currentMarketDate()).thenReturn(date);
        when(store.latest(date, ShortTermSnapshotStage.FINAL)).thenReturn(Optional.of(legacy));
        when(settings.finalDeadline()).thenReturn(LocalTime.of(14, 49, 40));
        when(store.expireUncertifiedFinal(legacy, deadline)).thenReturn(blocked);
        ShortTermScheduledSnapshotController controller = new ShortTermScheduledSnapshotController(
                tradingClock, store, settings, attestations);

        ShortTermScheduledSnapshot response = controller.latest();

        assertThat(response.status()).isEqualTo(ShortTermSnapshotStatus.DATA_BLOCKED);
        assertThat(response.report()).isNull();
        assertThat(response.blockedReasons()).containsExactly("FINAL_CERTIFICATION_PROOF_INVALID");
        verify(store).expireUncertifiedFinal(legacy, deadline);
        verify(attestations, never()).attest(raw);
    }

    @Test
    void hidesTheReportBodyWhileFinalCertificationIsPending() {
        LocalDate date = LocalDate.of(2026, 7, 23);
        Instant stagedAt = Instant.parse("2026-07-23T06:49:30Z");
        TradingClockService tradingClock = mock(TradingClockService.class);
        ShortTermScheduledSnapshotStore store = mock(ShortTermScheduledSnapshotStore.class);
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
        ShortTermReport raw = mock(ShortTermReport.class);
        ShortTermScheduledSnapshot pending = new ShortTermScheduledSnapshot(
                date + ":FINAL:rules-v4", date, ShortTermSnapshotStage.FINAL,
                ShortTermSnapshotStatus.FINAL_PENDING, 1, "rules-v4", "{}", stagedAt,
                stagedAt.minusSeconds(30), null, "尾盘终选正在提交认证", List.of(), raw);
        when(tradingClock.currentMarketDate()).thenReturn(date);
        when(store.latest(date, ShortTermSnapshotStage.FINAL)).thenReturn(Optional.of(pending));
        ShortTermScheduledSnapshotController controller = new ShortTermScheduledSnapshotController(
                tradingClock, store, settings, attestations);

        ShortTermScheduledSnapshot response = controller.latest();

        assertThat(response.status()).isEqualTo(ShortTermSnapshotStatus.FINAL_PENDING);
        assertThat(response.report()).isNull();
        verify(attestations, never()).attest(raw);
    }
}

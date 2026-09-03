package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.firstboard.FirstBoardMarketDay;
import com.aistock.research.firstboard.FirstBoardMarketDayStatus;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaderLearningServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-09-03");
    private final LeaderSnapshotStore store = mock(LeaderSnapshotStore.class);
    private final FirstBoardDailyBarStore bars = mock(FirstBoardDailyBarStore.class);
    private final TradingClockService clock = mock(TradingClockService.class);
    private LeaderLearningService service;

    @BeforeEach
    void setUp() {
        service = new LeaderLearningService(store, bars, clock);
    }

    @Test
    void doesNotPersistIntradaySnapshotBeforeFixedCloseSampleTime() {
        when(clock.currentMarketDateTime()).thenReturn(LocalDateTime.of(2026, 9, 3, 15, 9));

        assertThat(service.capture(snapshot())).isEmpty();

        verify(store, never()).saveSnapshotIfAbsent(any());
    }

    @Test
    void keepsOutcomePendingWhenFutureMarketDayIsIncomplete() {
        LeaderSnapshotStore.PendingOutcome pending = pending(PotentialLeaderSnapshot.Lane.POTENTIAL);
        when(store.pendingOutcomes()).thenReturn(List.of(pending));
        stubTradingDates(5);
        when(bars.marketDay(TRADE_DATE.plusDays(1))).thenReturn(Optional.empty());

        service.refreshPending(TRADE_DATE.plusDays(9));

        ArgumentCaptor<LeaderSnapshotStore.OutcomeResult> saved =
                ArgumentCaptor.forClass(LeaderSnapshotStore.OutcomeResult.class);
        verify(store).saveOutcome(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("PENDING_DATA");
        assertThat(saved.getValue().success()).isNull();
    }

    @Test
    void labelsPotentialSuccessOnlyWithLimitUpAndTopTwentyPercentReturn() {
        LeaderSnapshotStore.PendingOutcome pending = pending(PotentialLeaderSnapshot.Lane.POTENTIAL);
        when(store.pendingOutcomes()).thenReturn(List.of(pending));
        List<LocalDate> sessions = stubTradingDates(5);
        stubReadyDays(sessions);
        Map<String, BigDecimal> base = prices("10", "10", "10", "10", "10");
        when(bars.closingPrices(any(), eq(TRADE_DATE))).thenReturn(base);
        for (int index = 0; index < sessions.size(); index++) {
            Map<String, BigDecimal> prices = prices(
                    index == 0 ? "11" : "10.8",
                    "10.5", "10.4", "10.3", "10.2");
            when(bars.closingPrices(any(), eq(sessions.get(index)))).thenReturn(prices);
            when(bars.confirmedLimitUpSymbols(sessions.get(index)))
                    .thenReturn(index == 0 ? Set.of("600000") : Set.of());
        }

        service.refreshPending(sessions.get(4));

        ArgumentCaptor<LeaderSnapshotStore.OutcomeResult> saved =
                ArgumentCaptor.forClass(LeaderSnapshotStore.OutcomeResult.class);
        verify(store).saveOutcome(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("READY");
        assertThat(saved.getValue().success()).isTrue();
        assertThat(saved.getValue().returnT1()).isEqualByComparingTo("10.00");
    }

    @Test
    void labelsCompetitionSuccessWhenClosedLimitUpHeightRanksTopTwo() {
        LeaderSnapshotStore.PendingOutcome pending = pending(PotentialLeaderSnapshot.Lane.COMPETITION);
        when(store.pendingOutcomes()).thenReturn(List.of(pending));
        List<LocalDate> sessions = stubTradingDates(3);
        stubReadyDays(sessions);
        when(bars.closingPrices(any(), eq(TRADE_DATE))).thenReturn(prices("10", "10", "10", "10", "10"));
        for (LocalDate session : sessions) {
            when(bars.closingPrices(any(), eq(session))).thenReturn(prices("11", "10.5", "10", "10", "10"));
        }
        when(bars.confirmedLimitUpSymbols(TRADE_DATE)).thenReturn(Set.of("600000"));
        when(bars.confirmedLimitUpSymbols(sessions.get(0))).thenReturn(Set.of("600000", "600001"));
        when(bars.confirmedLimitUpSymbols(sessions.get(1))).thenReturn(Set.of());
        when(bars.confirmedLimitUpSymbols(sessions.get(2))).thenReturn(Set.of());

        service.refreshPending(sessions.get(2));

        ArgumentCaptor<LeaderSnapshotStore.OutcomeResult> saved =
                ArgumentCaptor.forClass(LeaderSnapshotStore.OutcomeResult.class);
        verify(store).saveOutcome(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("READY");
        assertThat(saved.getValue().success()).isTrue();
        assertThat(saved.getValue().maxBoardCount()).isEqualTo(2);
    }

    @Test
    void publishesWilsonIntervalOnlyAfterThirtyReadySamples() {
        when(store.cohortCounts(
                PotentialLeaderSnapshot.Lane.POTENTIAL, PotentialLeaderSnapshot.Grade.A, "HOT"))
                .thenReturn(new LeaderSnapshotStore.CohortCounts(29, 18))
                .thenReturn(new LeaderSnapshotStore.CohortCounts(30, 19));

        PotentialLeaderSnapshot.Calibration collecting = service.calibration(
                PotentialLeaderSnapshot.Lane.POTENTIAL, PotentialLeaderSnapshot.Grade.A, bd("80"));
        PotentialLeaderSnapshot.Calibration ready = service.calibration(
                PotentialLeaderSnapshot.Lane.POTENTIAL, PotentialLeaderSnapshot.Grade.A, bd("80"));

        assertThat(collecting.status()).isEqualTo(PotentialLeaderSnapshot.CalibrationStatus.COLLECTING);
        assertThat(collecting.sampleSize()).isEqualTo(29);
        assertThat(collecting.successRate()).isNull();
        assertThat(ready.status()).isEqualTo(PotentialLeaderSnapshot.CalibrationStatus.READY);
        assertThat(ready.sampleSize()).isEqualTo(30);
        assertThat(ready.lower95()).isNotNull();
        assertThat(ready.upper95()).isNotNull();
    }

    private List<LocalDate> stubTradingDates(int count) {
        List<LocalDate> sessions = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(TRADE_DATE::plusDays)
                .toList();
        for (int offset = 1; offset <= count; offset++) {
            when(clock.verifiedTradingDayAfter(TRADE_DATE, offset))
                    .thenReturn(Optional.of(sessions.get(offset - 1)));
        }
        return sessions;
    }

    private void stubReadyDays(List<LocalDate> sessions) {
        for (LocalDate date : sessions) {
            when(bars.marketDay(date)).thenReturn(Optional.of(new FirstBoardMarketDay(
                    date, date, FirstBoardMarketDayStatus.READY, 5, 5, 0, true,
                    "bars-v1", Instant.now(), Instant.now(), null, null)));
        }
    }

    private Map<String, BigDecimal> prices(String candidate, String... peers) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("600000", bd(candidate));
        for (int index = 0; index < peers.length; index++) {
            values.put("60000" + (index + 1), bd(peers[index]));
        }
        return values;
    }

    private LeaderSnapshotStore.PendingOutcome pending(PotentialLeaderSnapshot.Lane lane) {
        return new LeaderSnapshotStore.PendingOutcome(
                UUID.randomUUID(), lane, "600000", TRADE_DATE, LeaderRuleSet.RULE_VERSION,
                PotentialLeaderSnapshot.Grade.A, "HOT", "半导体", bd("10"),
                List.of("600000", "600001", "600002", "600003", "600004"));
    }

    private PotentialLeaderSnapshot snapshot() {
        return new PotentialLeaderSnapshot(
                PotentialLeaderSnapshot.Status.READY, TRADE_DATE, TRADE_DATE,
                Instant.parse("2026-09-03T07:00:00Z"), null,
                Instant.parse("2026-09-03T07:10:00Z"), false, LeaderRuleSet.RULE_VERSION,
                new PotentialLeaderSnapshot.BoardOptions(false, false),
                new PotentialLeaderSnapshot.MarketSummary(60, 10, 3, bd("85"), bd("30"), "强"),
                List.of(), List.of(), List.of(),
                new PotentialLeaderSnapshot.Coverage(1, 1, 1, 1, 0, 0, 0, 0, 0), List.of());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

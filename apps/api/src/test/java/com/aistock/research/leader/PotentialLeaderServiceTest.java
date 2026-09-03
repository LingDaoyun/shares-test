package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardAlertStatus;
import com.aistock.research.firstboard.FirstBoardBaselineService;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineAccess;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineStock;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermLimitUpBoardService;
import com.aistock.research.shortterm.ShortTermLimitUpBoardSnapshot;
import com.aistock.research.shortterm.ShortTermLimitUpSentiment;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.trading.TradingSessionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PotentialLeaderServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-09-03");
    private static final Instant QUOTE_TIME = Instant.parse("2026-09-03T06:30:00Z");

    private final EastMoneyClient eastMoneyClient = mock(EastMoneyClient.class);
    private final FirstBoardBaselineService baselineService = mock(FirstBoardBaselineService.class);
    private final ShortTermLimitUpBoardService boardService = mock(ShortTermLimitUpBoardService.class);
    private final TradingClockService tradingClock = mock(TradingClockService.class);
    private final LeaderDirectionEngine directionEngine = mock(LeaderDirectionEngine.class);
    private final LeaderCandidateEngine candidateEngine = mock(LeaderCandidateEngine.class);
    private final LeaderScoreEngine scoreEngine = mock(LeaderScoreEngine.class);
    private final DragonTigerGateway dragonTigerGateway = mock(DragonTigerGateway.class);
    private final LeaderLearningService learningService = mock(LeaderLearningService.class);
    private final MutableClock cacheClock = new MutableClock(Instant.parse("2026-09-03T06:30:00Z"));
    private PotentialLeaderService service;

    @BeforeEach
    void setUp() {
        when(tradingClock.currentMarketDate()).thenReturn(TRADE_DATE);
        when(tradingClock.isMarketClosedDay(TRADE_DATE)).thenReturn(false);
        when(tradingClock.currentSession()).thenReturn(new TradingSessionSnapshot(
                "AFTERNOON_CONTINUOUS", "下午连续竞价", true, false, false,
                "14:45-14:49", List.of(), List.of()));
        service = new PotentialLeaderService(
                eastMoneyClient, baselineService, boardService, tradingClock,
                directionEngine, candidateEngine, scoreEngine, dragonTigerGateway,
                learningService, cacheClock);
    }

    @Test
    void baselinePreparationReturnsPreparingWithoutFetchingCoreSources() {
        when(baselineService.leaderAccess(TRADE_DATE)).thenReturn(new FirstBoardLeaderBaselineAccess(
                FirstBoardAlertStatus.UPDATING, TRADE_DATE, TRADE_DATE.minusDays(1), null,
                Map.of(), null, 0, 0, 0, 0, List.of("候选池数据正在更新")));

        PotentialLeaderSnapshot snapshot = service.snapshot(false, false);

        assertThat(snapshot.status()).isEqualTo(PotentialLeaderSnapshot.Status.PREPARING);
        assertThat(snapshot.potentialCandidates()).isEmpty();
        verify(eastMoneyClient, never()).fetchAshareQuoteSnapshot(anyInt());
        verify(boardService, never()).snapshot(any());
        verify(dragonTigerGateway, never()).fetch(any(), any());
    }

    @Test
    void incompleteFullMarketQuoteReturnsUnavailable() {
        when(baselineService.leaderAccess(TRADE_DATE)).thenReturn(readyBaseline());
        when(eastMoneyClient.fetchAshareQuoteSnapshot(6000)).thenReturn(new AshareQuoteSnapshot(
                List.of(quote()), 6000, 5000, 4999, 1, false, "test", QUOTE_TIME));

        PotentialLeaderSnapshot snapshot = service.snapshot(false, false);

        assertThat(snapshot.status()).isEqualTo(PotentialLeaderSnapshot.Status.UNAVAILABLE);
        assertThat(snapshot.potentialCandidates()).isEmpty();
        assertThat(snapshot.competitionCandidates()).isEmpty();
    }

    @Test
    void dragonTigerFailureDoesNotClearReadyFormalRanking() {
        stubSuccessfulCore();
        PotentialLeaderSnapshot.Candidate candidate = candidate();
        when(scoreEngine.rankPotential(anyList(), anyList(), anyMap())).thenReturn(List.of(candidate));
        when(scoreEngine.rankCompetition(anyList(), anyList(), any(), anyMap(), anyMap()))
                .thenReturn(List.of());
        when(dragonTigerGateway.fetch(any(), any())).thenReturn(new DragonTigerGateway.DragonTigerBatch(
                null, Map.of("600000", new PotentialLeaderSnapshot.DragonTigerEvidence(
                PotentialLeaderSnapshot.DisclosureStatus.FETCH_FAILED, null, BigDecimal.ZERO,
                List.of(), List.of("龙虎榜数据获取失败"))), List.of("龙虎榜数据获取失败")));

        PotentialLeaderSnapshot snapshot = service.snapshot(false, false);

        assertThat(snapshot.status()).isEqualTo(PotentialLeaderSnapshot.Status.READY);
        assertThat(snapshot.potentialCandidates()).containsExactly(candidate);
        assertThat(snapshot.dataGaps()).contains("龙虎榜数据获取失败");
    }

    @Test
    void laterCoreFailureReturnsStaleCopyWithOriginalQuoteTimestamp() {
        stubSuccessfulCore();
        PotentialLeaderSnapshot.Candidate candidate = candidate();
        when(scoreEngine.rankPotential(anyList(), anyList(), anyMap())).thenReturn(List.of(candidate));
        when(scoreEngine.rankCompetition(anyList(), anyList(), any(), anyMap(), anyMap()))
                .thenReturn(List.of());
        when(dragonTigerGateway.fetch(any(), any())).thenReturn(
                new DragonTigerGateway.DragonTigerBatch(null, Map.of(), List.of()));
        when(eastMoneyClient.fetchAshareQuoteSnapshot(6000))
                .thenReturn(completeQuotes())
                .thenThrow(new IllegalStateException("quote source down"));

        PotentialLeaderSnapshot ready = service.snapshot(false, false);
        cacheClock.advance(Duration.ofSeconds(61));
        PotentialLeaderSnapshot stale = service.snapshot(false, false);

        assertThat(ready.status()).isEqualTo(PotentialLeaderSnapshot.Status.READY);
        assertThat(stale.status()).isEqualTo(PotentialLeaderSnapshot.Status.STALE);
        assertThat(stale.potentialCandidates()).containsExactly(candidate);
        assertThat(stale.quoteTimestamp()).isEqualTo(ready.quoteTimestamp());
        assertThat(stale.autoRefreshAllowed()).isFalse();
        assertThat(stale.dataGaps()).anyMatch(value -> value.contains("quote source down"));
    }

    @Test
    void forwardsGrowthAndStarSwitchesWithoutChangingBaselineAccess() {
        stubSuccessfulCore();
        when(dragonTigerGateway.fetch(any(), any())).thenReturn(
                new DragonTigerGateway.DragonTigerBatch(null, Map.of(), List.of()));
        when(scoreEngine.rankPotential(anyList(), anyList(), anyMap())).thenReturn(List.of());
        when(scoreEngine.rankCompetition(anyList(), anyList(), any(), anyMap(), anyMap()))
                .thenReturn(List.of());

        PotentialLeaderSnapshot snapshot = service.snapshot(true, true);

        assertThat(snapshot.boardOptions()).isEqualTo(new PotentialLeaderSnapshot.BoardOptions(true, true));
        verify(baselineService).leaderAccess(TRADE_DATE);
        verify(candidateEngine).select(any(), anyList(), any(), anyList(), eq(true), eq(true));
    }

    @Test
    void concurrentColdRequestsShareOneMarketRefresh() throws Exception {
        stubSuccessfulCore();
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowFetchToFinish = new CountDownLatch(1);
        when(eastMoneyClient.fetchAshareQuoteSnapshot(6000)).thenAnswer(ignored -> {
            fetchStarted.countDown();
            assertThat(allowFetchToFinish.await(2, TimeUnit.SECONDS)).isTrue();
            return completeQuotes();
        });
        when(dragonTigerGateway.fetch(any(), any())).thenReturn(
                new DragonTigerGateway.DragonTigerBatch(null, Map.of(), List.of()));
        when(scoreEngine.rankPotential(anyList(), anyList(), anyMap())).thenReturn(List.of());
        when(scoreEngine.rankCompetition(anyList(), anyList(), any(), anyMap(), anyMap()))
                .thenReturn(List.of());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PotentialLeaderSnapshot> first = executor.submit(() -> service.snapshot(false, false));
            assertThat(fetchStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<PotentialLeaderSnapshot> second = executor.submit(() -> service.snapshot(false, false));
            allowFetchToFinish.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(PotentialLeaderSnapshot.Status.READY);
            assertThat(second.get(2, TimeUnit.SECONDS).status()).isEqualTo(PotentialLeaderSnapshot.Status.READY);
            verify(eastMoneyClient, times(1)).fetchAshareQuoteSnapshot(6000);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refreshDurationDoesNotConsumeThePublishedCacheLifetime() {
        stubSuccessfulCore();
        when(eastMoneyClient.fetchAshareQuoteSnapshot(6000)).thenAnswer(ignored -> {
            cacheClock.advance(Duration.ofSeconds(61));
            return completeQuotes();
        });
        when(dragonTigerGateway.fetch(any(), any())).thenReturn(
                new DragonTigerGateway.DragonTigerBatch(null, Map.of(), List.of()));
        when(scoreEngine.rankPotential(anyList(), anyList(), anyMap())).thenReturn(List.of());
        when(scoreEngine.rankCompetition(anyList(), anyList(), any(), anyMap(), anyMap()))
                .thenReturn(List.of());

        PotentialLeaderSnapshot first = service.snapshot(false, false);
        PotentialLeaderSnapshot cached = service.snapshot(false, false);

        assertThat(first.status()).isEqualTo(PotentialLeaderSnapshot.Status.READY);
        assertThat(cached).isSameAs(first);
        verify(eastMoneyClient, times(1)).fetchAshareQuoteSnapshot(6000);
    }

    private void stubSuccessfulCore() {
        when(baselineService.leaderAccess(TRADE_DATE)).thenReturn(readyBaseline());
        when(eastMoneyClient.fetchAshareQuoteSnapshot(6000)).thenReturn(completeQuotes());
        when(boardService.snapshot(TRADE_DATE)).thenReturn(boardSnapshot());
        PotentialLeaderSnapshot.Direction direction = direction();
        when(directionEngine.rank(anyList(), anyList(), anyList())).thenReturn(List.of(direction));
        when(candidateEngine.select(any(), anyList(), any(), anyList(), anyBoolean(), anyBoolean()))
                .thenReturn(new LeaderCandidateEngine.CandidateUniverse(
                        List.of(), List.of(), new LeaderCandidateEngine.SelectionCounts(
                        1, 1, 1, 1, 1, 1, 0, 0)));
    }

    private FirstBoardLeaderBaselineAccess readyBaseline() {
        return new FirstBoardLeaderBaselineAccess(
                FirstBoardAlertStatus.READY, TRADE_DATE, TRADE_DATE.minusDays(1),
                Instant.parse("2026-09-02T08:20:00Z"),
                Map.of("600000", mock(FirstBoardLeaderBaselineStock.class)), null,
                1, 1, 0, 0, List.of());
    }

    private AshareQuoteSnapshot completeQuotes() {
        return new AshareQuoteSnapshot(
                List.of(quote()), 6000, 1, 1, 0, true, "test", QUOTE_TIME);
    }

    private EastMoneyQuote quote() {
        return new EastMoneyQuote(
                "600000", "测试股份", "沪深", "半导体", bd("10.8"), bd("8"), bd("5"),
                bd("1000000"), bd("100000000"), null, null, null, "test", "https://example.test",
                QUOTE_TIME, TRADE_DATE, QUOTE_TIME, bd("12000000000"), bd("10"), bd("10.9"),
                bd("9.9"), bd("1.5"), bd("8000000000"));
    }

    private ShortTermLimitUpBoardSnapshot boardSnapshot() {
        return new ShortTermLimitUpBoardSnapshot(
                TRADE_DATE, QUOTE_TIME, true, null, List.of(), true, List.of(), List.of(),
                new ShortTermLimitUpSentiment(
                        60, 10, 2, bd("14.29"), 3, 12, 4,
                        30, 10, 15, 5, bd("50"), "情绪强势", "test"),
                null, List.of());
    }

    private PotentialLeaderSnapshot.Direction direction() {
        return new PotentialLeaderSnapshot.Direction(
                "半导体", 1, bd("82"), bd("70"), bd("5"), 3, 1, 3,
                bd("500000000"), List.of("600000"));
    }

    private PotentialLeaderSnapshot.Candidate candidate() {
        return new PotentialLeaderSnapshot.Candidate(
                PotentialLeaderSnapshot.Lane.POTENTIAL, 1, PotentialLeaderSnapshot.Grade.A, bd("82"),
                "600000", "测试股份", "半导体", "MAIN", bd("10.8"), bd("8"), bd("1.5"),
                bd("5"), bd("100000000"), bd("8000000000"), bd("9.8"), bd("9.5"), bd("0.02"),
                bd("101"), false, 0, null, null, 0, BigDecimal.ZERO, List.of(), List.of(), List.of(),
                new PotentialLeaderSnapshot.DragonTigerEvidence(
                        PotentialLeaderSnapshot.DisclosureStatus.NOT_DISCLOSED, null, BigDecimal.ZERO,
                        List.of(), List.of()), null, "candidate-hash");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

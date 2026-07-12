package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeOutcomeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    private final TradeCaseRepository cases = mock(TradeCaseRepository.class);
    private final TradeFillRepository fills = mock(TradeFillRepository.class);
    private final TradeOutcomeRepository outcomes = mock(TradeOutcomeRepository.class);
    private final TradeMarketDataGateway gateway = mock(TradeMarketDataGateway.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AtomicBoolean transactionActive = new AtomicBoolean();
    private final Map<String, TradeOutcomeEntity> stored = new LinkedHashMap<>();
    private TradeOutcomeService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            transactionActive.set(true);
            return new SimpleTransactionStatus();
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            transactionActive.set(false);
            return null;
        }).when(transactions).commit(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            transactionActive.set(false);
            return null;
        }).when(transactions).rollback(any());

        TradeCaseEntity tradeCase = tradeCase("HOLDING");
        when(cases.findById("case-1")).thenReturn(Optional.of(tradeCase));
        when(cases.findByIdForUpdate("case-1")).thenReturn(Optional.of(tradeCase));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1")).thenReturn(List.of(
                fill("buy", "BUY", "2026-07-13T01:30:00Z", "10", 100)));
        when(outcomes.findByCaseIdAndBaselineTypeAndHorizon(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(key(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
        when(outcomes.findByCaseIdOrderByHorizonAsc("case-1"))
                .thenAnswer(invocation -> new ArrayList<>(stored.values()));
        when(outcomes.saveAll(any())).thenAnswer(invocation -> {
            List<TradeOutcomeEntity> entities = invocation.getArgument(0);
            entities.forEach(entity -> stored.put(
                    key(entity.getCaseId(), entity.getBaselineType(), entity.getHorizon()), entity));
            return entities;
        });
        when(gateway.dailyKLineSeries(anyString(), any(), any())).thenAnswer(invocation ->
                MarketKLineSeries.complete(
                        gateway.dailyKLines(
                                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)),
                        "TEST_DAILY_KLINE"));

        service = new TradeOutcomeService(
                cases, fills, outcomes, gateway, new TradeOutcomeCalculator(), new TradeLedgerCalculator(),
                transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void refreshesOutsideTransactionsAndUpsertsOneRowPerBaselineAndHorizon() {
        when(gateway.dailyKLines(anyString(), any(), any())).thenAnswer(invocation -> {
            assertThat(transactionActive.get()).isFalse();
            return twentyBars();
        });
        when(gateway.latestPrice("002714")).thenAnswer(invocation -> {
            assertThat(transactionActive.get()).isFalse();
            return Optional.of(latest("13", "2026-08-07", "2026-08-07T07:00:00Z"));
        });

        service.refresh("case-1");
        service.refresh("case-1");

        assertThat(stored.values())
                .extracting(entity -> entity.getBaselineType() + "/" + entity.getHorizon())
                .containsExactlyInAnyOrder(
                        "RECOMMENDATION/T1", "RECOMMENDATION/T5", "RECOMMENDATION/T20",
                        "RECOMMENDATION/CURRENT", "EXECUTION/CURRENT", "EXECUTION/CLOSED");
        assertThat(stored).hasSize(6);
        assertThat(stored.get(key("case-1", "EXECUTION", "CURRENT")).getReturnPct())
                .isEqualByComparingTo("30.0000");
        assertThat(stored.get(key("case-1", "EXECUTION", "CLOSED")).getStatus()).isEqualTo("PENDING");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T1")).getSourceName())
                .isEqualTo("TEST_DAILY_KLINE");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "CURRENT")).getSourceName())
                .isEqualTo("EAST_MONEY_LIVE_QUOTE");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "CURRENT")).getMarketTimestamp())
                .isEqualTo(Instant.parse("2026-08-07T07:00:00Z"));
        verify(outcomes, atLeastOnce()).saveAll(any());
    }

    @Test
    void advancesAndSavesTheLockedCaseVersionAfterEverySuccessfulReconciliation() {
        TradeCaseEntity tradeCase = tradeCase("HOLDING");
        when(cases.findById("case-1")).thenReturn(Optional.of(tradeCase));
        when(cases.findByIdForUpdate("case-1")).thenReturn(Optional.of(tradeCase));
        when(cases.save(tradeCase)).thenReturn(tradeCase);
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenReturn(Optional.of(
                latest("13", "2026-08-07", "2026-08-07T07:00:00Z")));
        Instant originalVersion = tradeCase.getUpdatedAt();

        service.refresh("case-1");
        Instant firstRefreshVersion = tradeCase.getUpdatedAt();
        service.refresh("case-1");

        assertThat(firstRefreshVersion).isAfter(originalVersion);
        assertThat(tradeCase.getUpdatedAt()).isAfter(firstRefreshVersion);
        verify(cases, times(2)).save(tradeCase);
    }

    @Test
    void keepsMatureSnapshotsWhenHistoryBecomesInsufficient() {
        TradeOutcomeEntity matureT5 = TradeOutcomeEntity.matured(
                "mature-t5", "case-1", "RECOMMENDATION", "T5", decimal("10"), decimal("12"),
                LocalDate.parse("2026-07-17"), decimal("20"), decimal("22"), decimal("-13"), NOW.minusSeconds(60));
        stored.put(key("case-1", "RECOMMENDATION", "T5"), matureT5);
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(List.of(
                new MarketBar(LocalDate.parse("2026-07-13"), decimal("11"), decimal("12"), decimal("9"))));
        when(gateway.latestPrice(anyString())).thenReturn(Optional.of(
                latest("11", "2026-08-07", "2026-08-07T07:00:00Z")));

        service.refresh("case-1");

        TradeOutcomeEntity retained = stored.get(key("case-1", "RECOMMENDATION", "T5"));
        assertThat(retained.getSnapshotId()).isEqualTo("mature-t5");
        assertThat(retained.getStatus()).isEqualTo("MATURED");
        assertThat(retained.getReturnPct()).isEqualByComparingTo("20");
    }

    @Test
    void persistsRecoverableUnavailableWithoutDowngradingMatureFixedOutcomeWhenGatewayFails() {
        TradeOutcomeEntity matureT1 = TradeOutcomeEntity.matured(
                "mature-t1", "case-1", "RECOMMENDATION", "T1", decimal("10"), decimal("11"),
                LocalDate.parse("2026-07-13"), decimal("10"), decimal("12"), decimal("-2"), NOW.minusSeconds(60));
        stored.put(key("case-1", "RECOMMENDATION", "T1"), matureT1);
        when(gateway.dailyKLines(anyString(), any(), any())).thenThrow(new IllegalStateException("market down"));

        TradeOutcomeRefresh result = service.refresh("case-1");

        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("market down"));
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T1"))).isSameAs(matureT1);
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T5")).getStatus())
                .isEqualTo("UNAVAILABLE");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T20")).getStatus())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void namesLastKlineCloseFallbackAndMarksEmptyHistoryUnavailable() {
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(List.of(
                new MarketBar(LocalDate.parse("2026-07-13"), decimal("11"), decimal("12"), decimal("9"))));
        when(gateway.latestPrice(anyString())).thenReturn(Optional.empty());

        TradeOutcomeRefresh result = service.refresh("case-1");

        assertThat(result.warnings()).contains("CURRENT 使用 LAST_KLINE_CLOSE_FALLBACK");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T5")).getStatus()).isEqualTo("PENDING");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T5")).getReturnPct()).isNull();
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "CURRENT")).getEvaluationPrice())
                .isEqualByComparingTo("11");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "CURRENT")).getSourceName())
                .isEqualTo("LAST_KLINE_CLOSE_FALLBACK");

        stored.clear();
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(List.of());
        service.refresh("case-1");

        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T1")).getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T20")).getEvaluationPrice()).isNull();
    }

    @Test
    void persistsClosedExecutionCashFlowOutcomeWithoutRequiringLivePrice() {
        TradeCaseEntity closed = tradeCase("CLOSED");
        when(cases.findById("case-1")).thenReturn(Optional.of(closed));
        when(cases.findByIdForUpdate("case-1")).thenReturn(Optional.of(closed));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1")).thenReturn(List.of(
                fill("buy", "BUY", "2026-07-13T01:30:00Z", "10", 100),
                fill("sell", "SELL", "2026-07-15T01:30:00Z", "12", 100)));
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenReturn(Optional.empty());

        service.refresh("case-1");

        TradeOutcomeEntity execution = stored.get(key("case-1", "EXECUTION", "CLOSED"));
        assertThat(execution.getReturnPct()).isEqualByComparingTo("20.0000");
        assertThat(execution.getEvaluationDate()).isEqualTo(LocalDate.parse("2026-07-15"));
    }

    @Test
    void weekendRefreshUsesTheQuotesFridayTradeDate() {
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenReturn(Optional.of(
                latest("13", "2026-08-07", "2026-08-07T07:00:00Z")));

        service.refresh("case-1");

        assertThat(stored.get(key("case-1", "RECOMMENDATION", "CURRENT")).getEvaluationDate())
                .isEqualTo(LocalDate.parse("2026-08-07"));
    }

    @Test
    void undatedQuoteCannotBePersistedAsLiveToday() {
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(List.of());
        when(gateway.latestPrice(anyString())).thenReturn(Optional.of(
                new LatestMarketPrice(decimal("13"), "EAST_MONEY_LIVE_QUOTE", null, null)));

        service.refresh("case-1");

        TradeOutcomeEntity current = stored.get(key("case-1", "RECOMMENDATION", "CURRENT"));
        assertThat(current.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(current.getEvaluationDate()).isNull();
        assertThat(current.getSourceName()).isEqualTo("LIVE_QUOTE_UNAVAILABLE");
    }

    @Test
    void olderRefreshCannotOverwriteNewerCurrentOutcome() {
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenAnswer(invocation -> {
            stored.put(key("case-1", "RECOMMENDATION", "CURRENT"), TradeOutcomeEntity.matured(
                    "newer", "case-1", "RECOMMENDATION", "CURRENT", decimal("10"), decimal("14"),
                    LocalDate.parse("2026-08-10"), decimal("40"), null, null,
                    "EAST_MONEY_LIVE_QUOTE", Instant.parse("2026-08-10T07:00:00Z"), NOW));
            return Optional.of(latest("13", "2026-08-07", "2026-08-07T07:00:00Z"));
        });

        service.refresh("case-1");

        assertThat(stored.get(key("case-1", "RECOMMENDATION", "CURRENT")).getEvaluationPrice())
                .isEqualByComparingTo("14");
    }

    @Test
    void fillMutationDuringMarketLoadPreventsStaleExecutionWrite() {
        TradeFillEntity original = fill("buy", "BUY", "2026-07-13T01:30:00Z", "10", 100);
        TradeFillEntity revised = fill("buy", "BUY", "2026-07-13T01:30:00Z", "12", 100);
        AtomicReference<List<TradeFillEntity>> currentFills = new AtomicReference<>(List.of(original));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1"))
                .thenAnswer(invocation -> currentFills.get());
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenAnswer(invocation -> {
            currentFills.set(List.of(revised));
            return Optional.of(latest("13", "2026-08-07", "2026-08-07T07:00:00Z"));
        });

        TradeOutcomeRefresh refresh = service.refresh("case-1");

        assertThat(refresh.warnings()).contains("复盘事实已变更，本次刷新未写入");
        assertThat(stored).isEmpty();
        verify(outcomes, never()).saveAll(any());
    }

    @Test
    void closedToHoldingTransitionClearsClosedAndRestoresCurrentExecution() {
        stored.put(key("case-1", "EXECUTION", "CLOSED"), executionOutcome("closed", "CLOSED", "12", "2026-07-15"));
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenReturn(Optional.of(
                latest("13", "2026-08-07", "2026-08-07T07:00:00Z")));

        service.refresh("case-1");

        assertThat(stored.get(key("case-1", "EXECUTION", "CLOSED")).getStatus()).isEqualTo("PENDING");
        assertThat(stored.get(key("case-1", "EXECUTION", "CLOSED")).getEvaluationPrice()).isNull();
        assertThat(stored.get(key("case-1", "EXECUTION", "CURRENT")).getStatus()).isEqualTo("MATURED");
    }

    @Test
    void deletingAllFillsClearsBothExecutionOutcomes() {
        stored.put(key("case-1", "EXECUTION", "CURRENT"), executionOutcome("current", "CURRENT", "13", "2026-08-07"));
        stored.put(key("case-1", "EXECUTION", "CLOSED"), executionOutcome("closed", "CLOSED", "12", "2026-07-15"));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1")).thenReturn(List.of());
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenReturn(Optional.of(
                latest("13", "2026-08-07", "2026-08-07T07:00:00Z")));

        service.refresh("case-1");

        assertThat(stored.get(key("case-1", "EXECUTION", "CURRENT")).getStatus()).isEqualTo("PENDING");
        assertThat(stored.get(key("case-1", "EXECUTION", "CLOSED")).getStatus()).isEqualTo("PENDING");
        assertThat(stored.get(key("case-1", "EXECUTION", "CURRENT")).getReturnPct()).isNull();
        assertThat(stored.get(key("case-1", "EXECUTION", "CLOSED")).getReturnPct()).isNull();
    }

    @Test
    void schedulerContinuesAfterCandidateSelectionFailsAndIncludesLaterCases() {
        TradeCaseEntity brokenClosedCase = tradeCase("case-1", "CLOSED");
        TradeCaseEntity laterClosedCase = tradeCase("case-2", "CLOSED");
        when(cases.findRefreshCandidates(any(Pageable.class)))
                .thenReturn(List.of(brokenClosedCase, laterClosedCase));
        when(cases.findById("case-1")).thenThrow(new IllegalStateException("candidate lookup failed"));
        when(cases.findById("case-2")).thenReturn(Optional.of(laterClosedCase));
        when(cases.findByIdForUpdate("case-2")).thenReturn(Optional.of(laterClosedCase));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-2")).thenReturn(List.of());
        when(gateway.dailyKLines("002714", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-08-10")))
                .thenReturn(List.of());
        when(gateway.latestPrice("002714")).thenReturn(Optional.empty());

        service.refreshOpenCases();

        verify(gateway, times(1)).dailyKLineSeries(anyString(), any(), any());
    }

    @Test
    void schedulerUsesBoundedOldestFirstBatchAndRefreshesClosedExecutionAfterT20Matures() {
        TradeCaseEntity closed = tradeCase("case-1", "CLOSED");
        when(cases.findRefreshCandidates(any(Pageable.class))).thenReturn(List.of(closed));
        stored.put(key("case-1", "RECOMMENDATION", "T20"), TradeOutcomeEntity.matured(
                "mature-t20", "case-1", "RECOMMENDATION", "T20", decimal("10"), decimal("12"),
                LocalDate.parse("2026-08-07"), decimal("20"), null, null, NOW.minusSeconds(60)));
        when(cases.findById("case-1")).thenReturn(Optional.of(closed));
        when(cases.findByIdForUpdate("case-1")).thenReturn(Optional.of(closed));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1")).thenReturn(List.of(
                fill("buy", "BUY", "2026-07-13T01:30:00Z", "10", 100),
                fill("sell", "SELL", "2026-07-15T01:30:00Z", "12", 100)));
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(twentyBars());
        when(gateway.latestPrice(anyString())).thenReturn(Optional.empty());

        service.refreshOpenCases();

        assertThat(stored.get(key("case-1", "EXECUTION", "CLOSED")).getStatus()).isEqualTo("MATURED");
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cases).findRefreshCandidates(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    private TradeCaseEntity tradeCase(String status) {
        return tradeCase("case-1", status);
    }

    private TradeCaseEntity tradeCase(String caseId, String status) {
        TradeCaseEntity tradeCase = TradeCaseEntity.planned(
                caseId, "fingerprint-" + caseId, "decision-1", "002714", "牧原股份", "MISPRICING",
                "分批建仓", decimal("78"), "mispricing-v2", decimal("10"),
                Instant.parse("2026-07-10T07:00:00Z"), "{}", Instant.parse("2026-07-10T07:00:00Z"));
        tradeCase.updateStatus(status, NOW.minusSeconds(60));
        return tradeCase;
    }

    private TradeFillEntity fill(String id, String side, String at, String price, long quantity) {
        Instant instant = Instant.parse(at);
        return TradeFillEntity.create(id, "case-1", side, instant, decimal(price), quantity, instant);
    }

    private LatestMarketPrice latest(String price, String date, String timestamp) {
        return new LatestMarketPrice(decimal(price), "EAST_MONEY_LIVE_QUOTE",
                LocalDate.parse(date), Instant.parse(timestamp));
    }

    private TradeOutcomeEntity executionOutcome(String id, String horizon, String price, String date) {
        return TradeOutcomeEntity.matured(
                id, "case-1", "EXECUTION", horizon, decimal("10"), decimal(price), LocalDate.parse(date),
                decimal("20"), null, null, "EXECUTION_FILLS", null, NOW.minusSeconds(60));
    }

    private List<MarketBar> twentyBars() {
        List<MarketBar> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-07-13");
        for (int index = 0; index < 20; index++) {
            BigDecimal close = decimal(String.valueOf(11 + index * 0.1));
            rows.add(new MarketBar(start.plusDays(index), close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE)));
        }
        return rows;
    }

    private String key(String caseId, String baseline, String horizon) {
        return caseId + "/" + baseline + "/" + horizon;
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

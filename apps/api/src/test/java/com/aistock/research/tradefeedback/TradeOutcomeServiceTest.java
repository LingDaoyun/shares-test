package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
            return Optional.of(new LatestMarketPrice(decimal("13"), "EAST_MONEY_LIVE_QUOTE"));
        });

        service.refresh("case-1");
        service.refresh("case-1");

        assertThat(stored.values())
                .extracting(entity -> entity.getBaselineType() + "/" + entity.getHorizon())
                .containsExactlyInAnyOrder(
                        "RECOMMENDATION/T1", "RECOMMENDATION/T5", "RECOMMENDATION/T20",
                        "RECOMMENDATION/CURRENT", "EXECUTION/CURRENT");
        assertThat(stored).hasSize(5);
        assertThat(stored.get(key("case-1", "EXECUTION", "CURRENT")).getReturnPct())
                .isEqualByComparingTo("30.0000");
        verify(outcomes, atLeastOnce()).saveAll(any());
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
                new LatestMarketPrice(decimal("11"), "EAST_MONEY_LIVE_QUOTE")));

        service.refresh("case-1");

        TradeOutcomeEntity retained = stored.get(key("case-1", "RECOMMENDATION", "T5"));
        assertThat(retained.getSnapshotId()).isEqualTo("mature-t5");
        assertThat(retained.getStatus()).isEqualTo("MATURED");
        assertThat(retained.getReturnPct()).isEqualByComparingTo("20");
    }

    @Test
    void returnsWarningAndLeavesSnapshotsUntouchedWhenGatewayFails() {
        TradeOutcomeEntity matureT1 = TradeOutcomeEntity.matured(
                "mature-t1", "case-1", "RECOMMENDATION", "T1", decimal("10"), decimal("11"),
                LocalDate.parse("2026-07-13"), decimal("10"), decimal("12"), decimal("-2"), NOW.minusSeconds(60));
        stored.put(key("case-1", "RECOMMENDATION", "T1"), matureT1);
        when(gateway.dailyKLines(anyString(), any(), any())).thenThrow(new IllegalStateException("market down"));

        TradeOutcomeRefresh result = service.refresh("case-1");

        assertThat(result.warnings()).singleElement().asString().contains("market down");
        assertThat(stored.values()).containsExactly(matureT1);
        verify(outcomes, never()).saveAll(any());
    }

    @Test
    void namesLastKlineCloseFallbackAndKeepsEmptyHistoryPending() {
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(List.of(
                new MarketBar(LocalDate.parse("2026-07-13"), decimal("11"), decimal("12"), decimal("9"))));
        when(gateway.latestPrice(anyString())).thenReturn(Optional.empty());

        TradeOutcomeRefresh result = service.refresh("case-1");

        assertThat(result.warnings()).contains("CURRENT 使用 LAST_KLINE_CLOSE_FALLBACK");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T5")).getStatus()).isEqualTo("PENDING");
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T5")).getReturnPct()).isNull();
        assertThat(stored.get(key("case-1", "RECOMMENDATION", "CURRENT")).getEvaluationPrice())
                .isEqualByComparingTo("11");

        stored.clear();
        when(gateway.dailyKLines(anyString(), any(), any())).thenReturn(List.of());
        service.refresh("case-1");

        assertThat(stored.get(key("case-1", "RECOMMENDATION", "T1")).getStatus()).isEqualTo("PENDING");
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
    void schedulerContinuesAfterCandidateSelectionFailsAndIncludesLaterCases() {
        TradeCaseEntity brokenClosedCase = tradeCase("case-1", "CLOSED");
        TradeCaseEntity laterClosedCase = tradeCase("case-2", "CLOSED");
        when(cases.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(brokenClosedCase, laterClosedCase));
        when(outcomes.findByCaseIdAndBaselineTypeAndHorizon("case-1", "RECOMMENDATION", "T20"))
                .thenThrow(new IllegalStateException("candidate lookup failed"));
        stored.put(key("case-2", "RECOMMENDATION", "T20"), TradeOutcomeEntity.pending(
                "case-2-pending-t20", "case-2", "RECOMMENDATION", "T20", NOW.minusSeconds(60)));
        when(cases.findById("case-2")).thenReturn(Optional.of(laterClosedCase));
        when(cases.findByIdForUpdate("case-2")).thenReturn(Optional.of(laterClosedCase));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-2")).thenReturn(List.of());
        when(gateway.dailyKLines("002714", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-08-10")))
                .thenReturn(List.of());
        when(gateway.latestPrice("002714")).thenReturn(Optional.empty());

        service.refreshOpenCases();

        verify(gateway, times(1)).dailyKLines(anyString(), any(), any());
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

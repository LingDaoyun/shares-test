package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeFeedbackServiceTest {

    private final TradeCaseRepository cases = mock(TradeCaseRepository.class);
    private final TradeFillRepository fills = mock(TradeFillRepository.class);
    private final TradeFillRevisionRepository revisions = mock(TradeFillRevisionRepository.class);
    private final TradeOutcomeRepository outcomes = mock(TradeOutcomeRepository.class);
    private final RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TradeFeedbackService service = new TradeFeedbackService(
            cases,
            fills,
            revisions,
            outcomes,
            new TradeFillProjector(),
            attestations,
            new TradeLedgerCalculator(),
            transactionManager,
            Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
    );

    @BeforeEach
    void startsIndependentCreateTransactions() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(attestations.require(anyString())).thenReturn(snapshot());
        when(revisions.findByCaseIdOrderByRevisionSequenceAsc(anyString())).thenReturn(List.of());
    }

    @Test
    void createsOneCasePerRecommendationAndTransitionsAsPositionChanges() {
        AtomicReference<TradeCaseEntity> storedCase = new AtomicReference<>();
        List<TradeFillEntity> storedFills = new ArrayList<>();
        when(cases.findByRecommendationFingerprint(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedCase.get()));
        when(cases.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedCase.get()));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> {
            TradeCaseEntity entity = invocation.getArgument(0);
            storedCase.set(entity);
            return entity;
        });
        when(cases.saveAndFlush(any(TradeCaseEntity.class))).thenAnswer(invocation -> {
            TradeCaseEntity entity = invocation.getArgument(0);
            storedCase.set(entity);
            return entity;
        });
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(anyString()))
                .thenAnswer(invocation -> List.copyOf(storedFills));
        when(fills.save(any(TradeFillEntity.class))).thenAnswer(invocation -> {
            TradeFillEntity entity = invocation.getArgument(0);
            storedFills.add(entity);
            return entity;
        });

        CreateTradeCaseRequest request = caseRequest();
        TradeCaseEntity created = service.createCase(request);

        assertThat(created.getCaseId())
                .isEqualTo(service.createCase(new CreateTradeCaseRequest("reissued-token")).getCaseId());
        assertThat(created.getRecommendationPayloadJson()).isEqualTo("{\"source\":\"test\"}");
        assertThat(service.addFill(created.getCaseId(), fill(TradeSide.BUY, "2026-07-13T01:35:00Z", "35", 100))
                .getStatus()).isEqualTo("HOLDING");
        assertThat(service.addFill(created.getCaseId(), fill(TradeSide.SELL, "2026-07-13T02:35:00Z", "36", 40))
                .getStatus()).isEqualTo("HOLDING");
        assertThat(service.addFill(created.getCaseId(), fill(TradeSide.SELL, "2026-07-13T03:35:00Z", "37", 60))
                .getStatus()).isEqualTo("CLOSED");
        assertThatThrownBy(() -> service.addFill(
                created.getCaseId(),
                fill(TradeSide.SELL, "2026-07-13T04:35:00Z", "38", 1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsCasesWithDatabaseFiltersKeysetCursorAndHardLimit() {
        when(cases.findCasePage(any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(List.of());
        Instant cursor = Instant.parse("2026-07-13T00:00:00Z");

        service.listCases("holding", "002714", cursor, "case-cursor", 10_000);

        verify(cases).findCasePage(
                eq("HOLDING"), eq("002714"), eq(cursor), eq("case-cursor"), eq(200));
    }

    @Test
    void calculatesPageLedgersWithOneBulkReadPerFactTable() {
        TradeFillEntity first = TradeFillEntity.create(
                "fill-1", "case-1", "BUY", Instant.parse("2026-07-13T01:00:00Z"),
                new BigDecimal("10"), 100, Instant.parse("2026-07-13T01:00:00Z"));
        when(fills.findByCaseIdInOrderByCaseIdAscExecutedAtAscCreatedAtAscFillIdAsc(any()))
                .thenReturn(List.of(first));
        when(revisions.findByCaseIdInOrderByCaseIdAscRevisionSequenceAsc(any()))
                .thenReturn(List.of());

        Map<String, TradeLedgerSummary> ledgers = service.ledgers(
                List.of("case-1", "case-2"), Map.of("case-1", new BigDecimal("11")));

        assertThat(ledgers.get("case-1").positionQuantity()).isEqualTo(100);
        assertThat(ledgers.get("case-2").positionQuantity()).isZero();
        verify(fills).findByCaseIdInOrderByCaseIdAscExecutedAtAscCreatedAtAscFillIdAsc(any());
        verify(revisions).findByCaseIdInOrderByCaseIdAscRevisionSequenceAsc(any());
    }

    @Test
    void derivesRecommendationFactsFromTheAttestation() {
        AtomicReference<TradeCaseEntity> storedCase = new AtomicReference<>();
        when(cases.findByRecommendationFingerprint(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedCase.get()));
        when(cases.saveAndFlush(any(TradeCaseEntity.class))).thenAnswer(invocation -> {
            TradeCaseEntity entity = invocation.getArgument(0);
            storedCase.set(entity);
            return entity;
        });
        CreateTradeCaseRequest request = new CreateTradeCaseRequest(" server-token ");
        CreateTradeCaseRequest equivalent = new CreateTradeCaseRequest("server-token");

        TradeCaseEntity created = service.createCase(request);

        assertThat(created.getRecommendationAction()).isEqualTo("分批建仓");
        assertThat(created.getSourceModule()).isEqualTo("MISPRICING");
        assertThat(created.isRecommendationVerified()).isTrue();
        assertThat(service.createCase(equivalent).getCaseId()).isEqualTo(created.getCaseId());
    }

    @Test
    void validatesTheAttestationBeforeReturningAnExistingCase() {
        when(attestations.require("expired-token"))
                .thenThrow(new IllegalArgumentException("推荐凭证已过期，请刷新推荐后重试"));
        when(cases.findByRecommendationFingerprint(anyString())).thenReturn(Optional.of(
                TradeCaseEntity.planned(
                        "existing", "fingerprint", null, "002714", "牧原股份", "MISPRICING",
                        "观察", null, "mispricing-v2", new BigDecimal("36.20"),
                        Instant.parse("2026-07-12T01:00:00Z"), "{}",
                        Instant.parse("2026-07-12T01:00:00Z"))));

        assertThatThrownBy(() -> service.createCase(new CreateTradeCaseRequest("expired-token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void reloadsTheExistingCaseAfterADuplicateCreateRollsBack() {
        TradeCaseEntity existing = plannedCase("PLANNED");
        when(cases.findByRecommendationFingerprint(anyString()))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(existing));
        when(cases.saveAndFlush(any(TradeCaseEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate recommendation fingerprint"));

        TradeCaseEntity result = service.createCase(caseRequest());

        assertThat(result).isSameAs(existing);
        verify(cases).saveAndFlush(any(TradeCaseEntity.class));
    }

    @Test
    void rejectsAnEditThatWouldCreateAnOversoldPrefix() {
        TradeCaseEntity tradeCase = plannedCase("HOLDING");
        TradeFillEntity buy = TradeFillEntity.create(
                "fill-buy", tradeCase.getCaseId(), "BUY", Instant.parse("2026-07-13T01:35:00Z"),
                new BigDecimal("35"), 100, Instant.parse("2026-07-12T01:00:00Z"));
        TradeFillEntity sell = TradeFillEntity.create(
                "fill-sell", tradeCase.getCaseId(), "SELL", Instant.parse("2026-07-13T03:35:00Z"),
                new BigDecimal("36"), 40, Instant.parse("2026-07-12T01:00:00Z"));
        when(cases.findByIdForUpdate(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of(buy, sell));

        assertThatThrownBy(() -> service.updateFill(
                tradeCase.getCaseId(), "fill-buy", fill(TradeSide.BUY, "2026-07-13T04:35:00Z", "35", 100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("卖出股数超过当前持仓");

        assertThat(buy.getExecutedAt()).isEqualTo(Instant.parse("2026-07-13T01:35:00Z"));
        verify(revisions, never()).save(any(TradeFillRevisionEntity.class));
    }

    @Test
    void acceptsFillsBeforeTheRecommendationButStillRejectsCancelledCases() {
        TradeCaseEntity planned = plannedCase("PLANNED");
        when(cases.findByIdForUpdate(planned.getCaseId())).thenReturn(Optional.of(planned));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(planned.getCaseId())).thenReturn(List.of());
        when(fills.save(any(TradeFillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TradeCaseEntity filled = service.addFill(
                planned.getCaseId(), fill(TradeSide.BUY, "2026-07-12T23:59:59Z", "35", 100)
        );

        assertThat(filled.getStatus()).isEqualTo("HOLDING");

        planned.updateStatus("CANCELLED", Instant.parse("2026-07-13T02:00:00Z"));
        assertThatThrownBy(() -> service.addFill(
                planned.getCaseId(), fill(TradeSide.BUY, "2026-07-13T01:35:00Z", "35", 100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已取消");
    }

    @Test
    void deletesAFillAndRecomputesTheCaseBackToPlanned() {
        TradeCaseEntity tradeCase = plannedCase("HOLDING");
        TradeFillEntity buy = TradeFillEntity.create(
                "fill-buy", tradeCase.getCaseId(), "BUY", Instant.parse("2026-07-13T01:35:00Z"),
                new BigDecimal("35"), 100, Instant.parse("2026-07-12T01:00:00Z"));
        when(cases.findByIdForUpdate(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of(buy));
        when(revisions.save(any(TradeFillRevisionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TradeCaseEntity result = service.deleteFill(tradeCase.getCaseId(), buy.getFillId());

        assertThat(result.getStatus()).isEqualTo("PLANNED");
        verify(revisions).save(org.mockito.ArgumentMatchers.argThat(
                revision -> "VOID".equals(revision.getRevisionType()) && buy.getFillId().equals(revision.getFillId())));
        verify(fills, never()).delete(buy);
    }

    @Test
    void cancelsOnlyAPlanWithoutFillsAndThenRejectsNewFills() {
        TradeCaseEntity tradeCase = plannedCase("PLANNED");
        when(cases.findByIdForUpdate(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of());

        TradeCaseEntity cancelled = service.cancelCase(tradeCase.getCaseId());

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.addFill(
                tradeCase.getCaseId(), fill(TradeSide.BUY, "2026-07-13T01:35:00Z", "35", 100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已取消");
    }

    @Test
    void deletesOnlyAPlannedOrCancelledCaseWithoutAnyTradeAuditTrail() {
        TradeCaseEntity tradeCase = plannedCase("PLANNED");
        when(cases.findByIdForUpdate(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of());
        when(revisions.existsByCaseId(tradeCase.getCaseId())).thenReturn(false);

        service.deleteCase(tradeCase.getCaseId());

        verify(outcomes).deleteByCaseId(tradeCase.getCaseId());
        verify(cases).delete(tradeCase);

        TradeCaseEntity cancelled = plannedCase("case-cancelled", "CANCELLED");
        when(cases.findByIdForUpdate(cancelled.getCaseId())).thenReturn(Optional.of(cancelled));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(cancelled.getCaseId())).thenReturn(List.of());
        when(revisions.existsByCaseId(cancelled.getCaseId())).thenReturn(false);

        service.deleteCase(cancelled.getCaseId());

        verify(outcomes).deleteByCaseId(cancelled.getCaseId());
        verify(cases).delete(cancelled);
    }

    @Test
    void rejectsDeletingCasesWithPositionsOrHistoricalFillRevisions() {
        TradeCaseEntity holding = plannedCase("HOLDING");
        when(cases.findByIdForUpdate(holding.getCaseId())).thenReturn(Optional.of(holding));

        assertThatThrownBy(() -> service.deleteCase(holding.getCaseId()))
                .isInstanceOf(TradeFeedbackConflictException.class)
                .hasMessageContaining("只有尚未成交的计划或已取消关注可以删除");

        TradeCaseEntity planned = plannedCase("PLANNED");
        when(cases.findByIdForUpdate(planned.getCaseId())).thenReturn(Optional.of(planned));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(planned.getCaseId())).thenReturn(List.of());
        when(revisions.existsByCaseId(planned.getCaseId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteCase(planned.getCaseId()))
                .isInstanceOf(TradeFeedbackConflictException.class)
                .hasMessageContaining("已有成交记录");

        verify(cases, never()).delete(any(TradeCaseEntity.class));
    }

    @Test
    void locksTheParentCaseBeforeReadingFillsForAMutation() {
        TradeCaseEntity tradeCase = plannedCase("PLANNED");
        when(cases.findByIdForUpdate(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of());
        when(fills.save(any(TradeFillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addFill(tradeCase.getCaseId(), fill(TradeSide.BUY, "2026-07-13T01:35:00Z", "35", 100));

        verify(cases).findByIdForUpdate(tradeCase.getCaseId());
        verify(cases, never()).findById(tradeCase.getCaseId());
    }

    private CreateTradeCaseRequest caseRequest() {
        return new CreateTradeCaseRequest("server-token");
    }

    private VerifiedRecommendationSnapshot snapshot() {
        return new VerifiedRecommendationSnapshot(
                "attestation-id",
                "002714", "牧原股份", "MISPRICING", "分批建仓", new BigDecimal("78"),
                "mispricing-v2", new BigDecimal("36.20"), Instant.parse("2026-07-13T01:00:00Z"),
                "{\"source\":\"test\"}");
    }

    private UpsertTradeFillRequest fill(TradeSide side, String executedAt, String price, long quantity) {
        return new UpsertTradeFillRequest(side, Instant.parse(executedAt), new BigDecimal(price), quantity);
    }

    private TradeCaseEntity plannedCase(String status) {
        return plannedCase("case-1", status);
    }

    private TradeCaseEntity plannedCase(String caseId, String status) {
        TradeCaseEntity tradeCase = TradeCaseEntity.planned(
                caseId, "fingerprint-" + caseId, "decision-1", "002714", "牧原股份", "MISPRICING",
                "分批建仓", new BigDecimal("78"), "mispricing-v2", new BigDecimal("36.20"),
                Instant.parse("2026-07-13T01:00:00Z"), "{}", Instant.parse("2026-07-12T01:00:00Z"));
        tradeCase.updateStatus(status, Instant.parse("2026-07-12T01:00:00Z"));
        return tradeCase;
    }
}

package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeFeedbackCriticalServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-12T04:00:00Z");
    private static final Instant RECOMMENDED_AT = NOW.minus(Duration.ofHours(1));

    private final TradeCaseRepository cases = mock(TradeCaseRepository.class);
    private final TradeFillRepository fills = mock(TradeFillRepository.class);
    private final TradeFillRevisionRepository revisions = mock(TradeFillRevisionRepository.class);
    private final TradeOutcomeRepository outcomes = mock(TradeOutcomeRepository.class);
    private final RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TradeFeedbackService service = new TradeFeedbackService(
            cases,
            fills,
            revisions,
            outcomes,
            new TradeFillProjector(),
            attestations,
            new TradeLedgerCalculator(),
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5));

    @BeforeEach
    void transactionSupport() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void createDerivesEveryRecommendationFactFromTheServerAttestation() {
        AtomicReference<TradeCaseEntity> stored = new AtomicReference<>();
        when(attestations.require("server-token")).thenReturn(snapshot());
        when(cases.findByRecommendationFingerprint(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(cases.saveAndFlush(any(TradeCaseEntity.class))).thenAnswer(invocation -> {
            TradeCaseEntity entity = invocation.getArgument(0);
            stored.set(entity);
            return entity;
        });

        TradeCaseEntity created = service.createCase(new CreateTradeCaseRequest("server-token"));

        assertThat(created.isRecommendationVerified()).isTrue();
        assertThat(created.getSymbol()).isEqualTo("002714");
        assertThat(created.getCompanyName()).isEqualTo("牧原股份");
        assertThat(created.getSourceModule()).isEqualTo("MISPRICING");
        assertThat(created.getRuleVersion()).isEqualTo("mispricing-v2");
        assertThat(created.getRecommendationPayloadJson()).isEqualTo("{\"server\":\"canonical\"}");
        assertThat(service.createCase(new CreateTradeCaseRequest("server-token")).getCaseId())
                .isEqualTo(created.getCaseId());
        verify(attestations, times(2)).require("server-token");
    }

    @Test
    void createRejectsAClientTokenThatWasNotIssuedByARecommendationResponse() {
        when(cases.findByRecommendationFingerprint(anyString())).thenReturn(Optional.empty());
        when(attestations.require("forged-token"))
                .thenThrow(new IllegalArgumentException("推荐凭证无效或已过期"));

        assertThatThrownBy(() -> service.createCase(new CreateTradeCaseRequest("forged-token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("推荐凭证");

        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void updateAndDeleteAppendAuditEventsWithoutMutatingOrDeletingTheOriginalFill() {
        TradeCaseEntity tradeCase = trustedCase("HOLDING");
        TradeFillEntity original = TradeFillEntity.create(
                "fill-1", "case-1", "BUY", RECOMMENDED_AT.plusSeconds(60),
                new BigDecimal("35"), 100, RECOMMENDED_AT.plusSeconds(61));
        List<TradeFillRevisionEntity> storedRevisions = new ArrayList<>();
        when(cases.findByIdForUpdate("case-1")).thenReturn(Optional.of(tradeCase));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1")).thenReturn(List.of(original));
        when(revisions.findByCaseIdOrderByRevisionSequenceAsc("case-1"))
                .thenAnswer(invocation -> List.copyOf(storedRevisions));
        when(revisions.save(any(TradeFillRevisionEntity.class))).thenAnswer(invocation -> {
            TradeFillRevisionEntity revision = invocation.getArgument(0);
            storedRevisions.add(revision);
            return revision;
        });

        service.updateFill("case-1", "fill-1", fill("36", 200, RECOMMENDED_AT.plusSeconds(120)));
        service.deleteFill("case-1", "fill-1");

        assertThat(original.getPrice()).isEqualByComparingTo("35");
        assertThat(original.getQuantity()).isEqualTo(100);
        assertThat(storedRevisions).extracting(TradeFillRevisionEntity::getRevisionType)
                .containsExactly("CORRECTION", "VOID");
        verify(fills, never()).save(original);
        verify(fills, never()).delete(original);
    }

    @Test
    void rejectsExecutionTimestampsBeyondTheExplicitClockSkewAllowance() {
        TradeCaseEntity tradeCase = trustedCase("PLANNED");
        when(cases.findByIdForUpdate("case-1")).thenReturn(Optional.of(tradeCase));

        assertThatThrownBy(() -> service.addFill(
                "case-1", fill("35", 100, NOW.plus(Duration.ofMinutes(5)).plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未来");

        verify(fills, never()).save(any());
    }

    private VerifiedRecommendationSnapshot snapshot() {
        return new VerifiedRecommendationSnapshot(
                "attestation-id",
                "002714", "牧原股份", "MISPRICING", "分批建仓", new BigDecimal("78"),
                "mispricing-v2", new BigDecimal("36.20"), RECOMMENDED_AT,
                "{\"server\":\"canonical\"}");
    }

    private TradeCaseEntity trustedCase(String status) {
        TradeCaseEntity tradeCase = TradeCaseEntity.verifiedPlanned(
                "case-1", "fingerprint", "attestation", "002714", "牧原股份", "MISPRICING",
                "分批建仓", new BigDecimal("78"), "mispricing-v2", new BigDecimal("36.20"),
                RECOMMENDED_AT, "{}", RECOMMENDED_AT);
        tradeCase.updateStatus(status, RECOMMENDED_AT);
        return tradeCase;
    }

    private UpsertTradeFillRequest fill(String price, long quantity, Instant executedAt) {
        return new UpsertTradeFillRequest(TradeSide.BUY, executedAt, new BigDecimal(price), quantity);
    }
}

package com.aistock.research.tradefeedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeFeedbackServiceTest {

    private final TradeCaseRepository cases = mock(TradeCaseRepository.class);
    private final TradeFillRepository fills = mock(TradeFillRepository.class);
    private final TradeFeedbackService service = new TradeFeedbackService(
            cases,
            fills,
            new ObjectMapper(),
            new TradeLedgerCalculator()
    );

    @Test
    void createsOneCasePerRecommendationAndTransitionsAsPositionChanges() {
        AtomicReference<TradeCaseEntity> storedCase = new AtomicReference<>();
        List<TradeFillEntity> storedFills = new ArrayList<>();
        when(cases.findByRecommendationFingerprint(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedCase.get()));
        when(cases.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedCase.get()));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> {
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
                .isEqualTo(service.createCase(request).getCaseId());
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
    void preservesRecommendationFactsWhileNormalizingTheIdempotencyFingerprint() {
        AtomicReference<TradeCaseEntity> storedCase = new AtomicReference<>();
        when(cases.findByRecommendationFingerprint(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedCase.get()));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> {
            TradeCaseEntity entity = invocation.getArgument(0);
            storedCase.set(entity);
            return entity;
        });
        CreateTradeCaseRequest request = new CreateTradeCaseRequest(
                " decision-1 ", " 002714 ", "牧原股份", " mispricing ", "Build Position",
                new BigDecimal("78"), " mispricing-v2 ", new BigDecimal("36.20"),
                Instant.parse("2026-07-13T01:00:00Z"), java.util.Map.of());
        CreateTradeCaseRequest equivalent = new CreateTradeCaseRequest(
                "DECISION-1", "002714", "牧原股份", "MISPRICING", "Build Position",
                new BigDecimal("78"), "MISPRICING-V2", new BigDecimal("36.20"),
                Instant.parse("2026-07-13T01:00:00Z"), java.util.Map.of());

        TradeCaseEntity created = service.createCase(request);

        assertThat(created.getRecommendationAction()).isEqualTo("Build Position");
        assertThat(created.getSourceModule()).isEqualTo("mispricing");
        assertThat(service.createCase(equivalent).getCaseId()).isEqualTo(created.getCaseId());
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
        when(cases.findById(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(fills.findById("fill-buy")).thenReturn(Optional.of(buy));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of(buy, sell));

        assertThatThrownBy(() -> service.updateFill(
                tradeCase.getCaseId(), "fill-buy", fill(TradeSide.BUY, "2026-07-13T04:35:00Z", "35", 100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("卖出股数超过当前持仓");

        assertThat(buy.getExecutedAt()).isEqualTo(Instant.parse("2026-07-13T01:35:00Z"));
        verify(fills, never()).save(any(TradeFillEntity.class));
    }

    @Test
    void rejectsFillsBeforeTheRecommendationAndOnCancelledCases() {
        TradeCaseEntity planned = plannedCase("PLANNED");
        when(cases.findById(planned.getCaseId())).thenReturn(Optional.of(planned));

        assertThatThrownBy(() -> service.addFill(
                planned.getCaseId(), fill(TradeSide.BUY, "2026-07-12T23:59:59Z", "35", 100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能早于推荐时间");

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
        when(cases.findById(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fills.findById(buy.getFillId())).thenReturn(Optional.of(buy));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of(buy));

        TradeCaseEntity result = service.deleteFill(tradeCase.getCaseId(), buy.getFillId());

        assertThat(result.getStatus()).isEqualTo("PLANNED");
        verify(fills).delete(buy);
    }

    @Test
    void cancelsOnlyAPlanWithoutFillsAndThenRejectsNewFills() {
        TradeCaseEntity tradeCase = plannedCase("PLANNED");
        when(cases.findById(tradeCase.getCaseId())).thenReturn(Optional.of(tradeCase));
        when(cases.save(any(TradeCaseEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())).thenReturn(List.of());

        TradeCaseEntity cancelled = service.cancelCase(tradeCase.getCaseId());

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.addFill(
                tradeCase.getCaseId(), fill(TradeSide.BUY, "2026-07-13T01:35:00Z", "35", 100)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已取消");
    }

    private CreateTradeCaseRequest caseRequest() {
        return new CreateTradeCaseRequest(
                "decision-1",
                " 002714 ",
                "牧原股份",
                "MISPRICING",
                "分批建仓",
                new BigDecimal("78"),
                "mispricing-v2",
                new BigDecimal("36.20"),
                Instant.parse("2026-07-13T01:00:00Z"),
                java.util.Map.of("source", "test")
        );
    }

    private UpsertTradeFillRequest fill(TradeSide side, String executedAt, String price, long quantity) {
        return new UpsertTradeFillRequest(side, Instant.parse(executedAt), new BigDecimal(price), quantity);
    }

    private TradeCaseEntity plannedCase(String status) {
        TradeCaseEntity tradeCase = TradeCaseEntity.planned(
                "case-1", "fingerprint", "decision-1", "002714", "牧原股份", "MISPRICING",
                "分批建仓", new BigDecimal("78"), "mispricing-v2", new BigDecimal("36.20"),
                Instant.parse("2026-07-13T01:00:00Z"), "{}", Instant.parse("2026-07-12T01:00:00Z"));
        tradeCase.updateStatus(status, Instant.parse("2026-07-12T01:00:00Z"));
        return tradeCase;
    }
}

package com.aistock.research.shortterm.validation;

import com.aistock.research.tradefeedback.MarketBar;
import com.aistock.research.tradefeedback.MarketKLineSeries;
import com.aistock.research.tradefeedback.TradeMarketDataGateway;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermOutcomeMaturationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T08:30:00Z");
    private final ShortTermSignalObservationRepository observations = mock(ShortTermSignalObservationRepository.class);
    private final ShortTermSignalOutcomeRepository outcomes = mock(ShortTermSignalOutcomeRepository.class);
    private final TradeMarketDataGateway marketData = mock(TradeMarketDataGateway.class);
    private final TradingClockService tradingClock = mock(TradingClockService.class);
    private final ShortTermValidationSettings settings = mock(ShortTermValidationSettings.class);
    private ShortTermOutcomeMaturationService service;

    @BeforeEach
    void setUp() {
        when(settings.enabled()).thenReturn(true);
        when(settings.batchSize()).thenReturn(100);
        service = new ShortTermOutcomeMaturationService(
                observations,
                outcomes,
                marketData,
                tradingClock,
                settings,
                new ShortTermHorizonOutcomeCalculator(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void maturesT1AndT2OnTheirExactMarketTradingDays() {
        ShortTermSignalObservationEntity observation = observation();
        List<ShortTermSignalOutcomeEntity> slots = outcomeSlots(observation);
        when(observations.findByValidationEligibleTrueAndOutcomeStateOrderByPublishedAtAsc(
                eq("PENDING"), any(Pageable.class))).thenReturn(List.of(observation));
        when(outcomes.findByObservationIdOrderByHorizonAsc(observation.getObservationId())).thenReturn(slots);
        slots.forEach(slot -> when(tradingClock.isCompletedDailyBar(slot.getTargetTradeDate())).thenReturn(true));
        when(marketData.dailyKLineSeries(
                observation.getSymbol(),
                LocalDate.parse("2026-08-13"),
                LocalDate.parse("2026-08-14")))
                .thenReturn(MarketKLineSeries.complete(List.of(
                        bar("2026-08-13", "102", "104", "97"),
                        bar("2026-08-14", "105", "106", "101")
                ), "EAST_MONEY_KLINE"));

        ShortTermOutcomeRefreshResult result = service.refreshPending();

        assertThat(result.observationCount()).isEqualTo(1);
        assertThat(result.maturedCount()).isEqualTo(2);
        assertThat(slots).extracting(ShortTermSignalOutcomeEntity::getStatus)
                .containsExactly("MATURED", "MATURED");
        assertThat(observation.getOutcomeState()).isEqualTo("COMPLETE");
        verify(observations).save(observation);
        verify(outcomes).saveAll(slots);
    }

    @Test
    void sourceFailureRemainsRetryableAndCanMatureLater() {
        ShortTermSignalObservationEntity observation = observation();
        List<ShortTermSignalOutcomeEntity> slots = outcomeSlots(observation);
        when(observations.findByValidationEligibleTrueAndOutcomeStateOrderByPublishedAtAsc(
                eq("PENDING"), any(Pageable.class))).thenReturn(List.of(observation));
        when(outcomes.findByObservationIdOrderByHorizonAsc(observation.getObservationId())).thenReturn(slots);
        slots.forEach(slot -> when(tradingClock.isCompletedDailyBar(slot.getTargetTradeDate())).thenReturn(true));
        when(marketData.dailyKLineSeries(any(), any(), any()))
                .thenReturn(MarketKLineSeries.unavailable("EAST_MONEY_KLINE", "upstream timeout"))
                .thenReturn(MarketKLineSeries.complete(List.of(
                        bar("2026-08-13", "102", "104", "97"),
                        bar("2026-08-14", "105", "106", "101")
                ), "EAST_MONEY_KLINE"));

        ShortTermOutcomeRefreshResult first = service.refreshPending();
        ShortTermOutcomeRefreshResult second = service.refreshPending();

        assertThat(first.retryableCount()).isEqualTo(2);
        assertThat(second.maturedCount()).isEqualTo(2);
        assertThat(slots).extracting(ShortTermSignalOutcomeEntity::getStatus)
                .containsExactly("MATURED", "MATURED");
    }

    @Test
    void completeSourceMarksOnlyTheMissingTargetHorizonUnavailable() {
        ShortTermSignalObservationEntity observation = observation();
        List<ShortTermSignalOutcomeEntity> slots = outcomeSlots(observation);
        when(observations.findByValidationEligibleTrueAndOutcomeStateOrderByPublishedAtAsc(
                eq("PENDING"), any(Pageable.class))).thenReturn(List.of(observation));
        when(outcomes.findByObservationIdOrderByHorizonAsc(observation.getObservationId())).thenReturn(slots);
        slots.forEach(slot -> when(tradingClock.isCompletedDailyBar(slot.getTargetTradeDate())).thenReturn(true));
        when(marketData.dailyKLineSeries(any(), any(), any())).thenReturn(MarketKLineSeries.complete(
                List.of(bar("2026-08-14", "105", "106", "101")), "EAST_MONEY_KLINE"));

        service.refreshPending();

        assertThat(slots.get(0).getStatus()).isEqualTo("UNAVAILABLE_SUSPENDED_OR_MISSING");
        assertThat(slots.get(1).getStatus()).isEqualTo("MATURED");
        assertThat(observation.getOutcomeState()).isEqualTo("COMPLETE");
    }

    @Test
    void doesNotCallMarketSourceBeforeAnyHorizonHasClosed() {
        ShortTermSignalObservationEntity observation = observation();
        List<ShortTermSignalOutcomeEntity> slots = outcomeSlots(observation);
        when(observations.findByValidationEligibleTrueAndOutcomeStateOrderByPublishedAtAsc(
                eq("PENDING"), any(Pageable.class))).thenReturn(List.of(observation));
        when(outcomes.findByObservationIdOrderByHorizonAsc(observation.getObservationId())).thenReturn(slots);
        slots.forEach(slot -> when(tradingClock.isCompletedDailyBar(slot.getTargetTradeDate())).thenReturn(false));

        ShortTermOutcomeRefreshResult result = service.refreshPending();

        assertThat(result.maturedCount()).isZero();
        verify(marketData, never()).dailyKLineSeries(any(), any(), any());
    }

    @Test
    void terminalOutcomeCannotBeOverwritten() {
        ShortTermSignalOutcomeEntity outcome = outcomeSlots(observation()).get(0);
        ShortTermHorizonEvaluation evaluation = new ShortTermHorizonOutcomeCalculator().evaluate(
                "T1",
                LocalDate.parse("2026-08-12"),
                LocalDate.parse("2026-08-13"),
                decimal("100"),
                List.of(bar("2026-08-13", "102", "104", "97")),
                observation().costAssumptions()
        );
        outcome.applyEvaluation(evaluation, "EAST_MONEY_KLINE", NOW, NOW);

        assertThatThrownBy(() -> outcome.applyEvaluation(evaluation, "OTHER", NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可覆盖");
    }

    @Test
    void repairsPendingObservationWhenAllOutcomeSlotsAreAlreadyTerminal() {
        ShortTermSignalObservationEntity observation = observation();
        List<ShortTermSignalOutcomeEntity> slots = outcomeSlots(observation);
        for (ShortTermSignalOutcomeEntity slot : slots) {
            slot.applyEvaluation(
                    new ShortTermHorizonEvaluation(
                            slot.getHorizon(),
                            "MATURED",
                            slot.getTargetTradeDate(),
                            decimal("102"),
                            decimal("2"),
                            decimal("1.8"),
                            decimal("3"),
                            decimal("-1"),
                            "test"
                    ),
                    "EAST_MONEY_KLINE",
                    NOW,
                    NOW
            );
        }
        when(observations.findByValidationEligibleTrueAndOutcomeStateOrderByPublishedAtAsc(
                eq("PENDING"), any(Pageable.class))).thenReturn(List.of(observation));
        when(outcomes.findByObservationIdOrderByHorizonAsc(observation.getObservationId()))
                .thenReturn(slots);

        service.refreshPending();

        assertThat(observation.getOutcomeState()).isEqualTo("COMPLETE");
        verify(observations).save(observation);
        verify(marketData, never()).dailyKLineSeries(any(), any(), any());
    }

    private ShortTermSignalObservationEntity observation() {
        Instant createdAt = Instant.parse("2026-08-12T06:49:00Z");
        return new ShortTermSignalObservationEntity(
                "obs-1", "scheduled-final-1", "SCHEDULED_FINAL",
                "short-term-right-side-v4-transparent-ranking", "600001", "样本公司", 1,
                "WAIT", "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", decimal("100"),
                LocalDate.parse("2026-08-12"), createdAt, createdAt, "EAST_MONEY", BigDecimal.ONE,
                5885, 5885, true, true, null, "{}",
                new ShortTermValidationCostAssumptions(
                        decimal("0.03"), decimal("0.03"), decimal("0.05"), decimal("0.05"), decimal("0.05")),
                createdAt
        );
    }

    private List<ShortTermSignalOutcomeEntity> outcomeSlots(ShortTermSignalObservationEntity observation) {
        return List.of(
                ShortTermSignalOutcomeEntity.pending(
                        "outcome-t1", observation.getObservationId(), "T1",
                        LocalDate.parse("2026-08-13"), observation.getCreatedAt()),
                ShortTermSignalOutcomeEntity.pending(
                        "outcome-t2", observation.getObservationId(), "T2",
                        LocalDate.parse("2026-08-14"), observation.getCreatedAt())
        );
    }

    private MarketBar bar(String date, String close, String high, String low) {
        return new MarketBar(LocalDate.parse(date), decimal(close), decimal(high), decimal(low));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

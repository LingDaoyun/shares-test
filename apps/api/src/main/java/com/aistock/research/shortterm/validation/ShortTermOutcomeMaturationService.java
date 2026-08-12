package com.aistock.research.shortterm.validation;

import com.aistock.research.tradefeedback.MarketKLineSeries;
import com.aistock.research.tradefeedback.TradeMarketDataGateway;
import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Service
public class ShortTermOutcomeMaturationService {

    private static final Logger log = LoggerFactory.getLogger(ShortTermOutcomeMaturationService.class);
    private static final ZoneId SHANGHAI = TradingClockService.CHINA_MARKET_ZONE;
    private static final LocalTime DAILY_BAR_TIMESTAMP = LocalTime.of(15, 0);

    private final ShortTermSignalObservationRepository observationRepository;
    private final ShortTermSignalOutcomeRepository outcomeRepository;
    private final TradeMarketDataGateway marketDataGateway;
    private final TradingClockService tradingClock;
    private final ShortTermValidationSettings settings;
    private final ShortTermHorizonOutcomeCalculator calculator;
    private final Clock clock;

    @Autowired
    public ShortTermOutcomeMaturationService(
            ShortTermSignalObservationRepository observationRepository,
            ShortTermSignalOutcomeRepository outcomeRepository,
            TradeMarketDataGateway marketDataGateway,
            TradingClockService tradingClock,
            ShortTermValidationSettings settings,
            ShortTermHorizonOutcomeCalculator calculator
    ) {
        this(
                observationRepository,
                outcomeRepository,
                marketDataGateway,
                tradingClock,
                settings,
                calculator,
                Clock.system(SHANGHAI)
        );
    }

    ShortTermOutcomeMaturationService(
            ShortTermSignalObservationRepository observationRepository,
            ShortTermSignalOutcomeRepository outcomeRepository,
            TradeMarketDataGateway marketDataGateway,
            TradingClockService tradingClock,
            ShortTermValidationSettings settings,
            ShortTermHorizonOutcomeCalculator calculator,
            Clock clock
    ) {
        this.observationRepository = observationRepository;
        this.outcomeRepository = outcomeRepository;
        this.marketDataGateway = marketDataGateway;
        this.tradingClock = tradingClock;
        this.settings = settings;
        this.calculator = calculator;
        this.clock = clock == null ? Clock.system(SHANGHAI) : clock;
    }

    public ShortTermOutcomeRefreshResult refreshPending() {
        if (!settings.enabled()) {
            return ShortTermOutcomeRefreshResult.empty();
        }
        List<ShortTermSignalObservationEntity> observations = observationRepository
                .findByValidationEligibleTrueAndOutcomeStateOrderByPublishedAtAsc(
                        "PENDING", PageRequest.of(0, settings.batchSize()));
        MutableCounts counts = new MutableCounts(observations.size());
        for (ShortTermSignalObservationEntity observation : observations) {
            refreshObservation(observation, counts);
        }
        return counts.snapshot();
    }

    private void refreshObservation(
            ShortTermSignalObservationEntity observation,
            MutableCounts counts
    ) {
        List<ShortTermSignalOutcomeEntity> slots = outcomeRepository
                .findByObservationIdOrderByHorizonAsc(observation.getObservationId());
        List<ShortTermSignalOutcomeEntity> due = slots.stream()
                .filter(slot -> !slot.terminal())
                .filter(slot -> tradingClock.isCompletedDailyBar(slot.getTargetTradeDate()))
                .toList();
        if (due.isEmpty()) {
            if (!slots.isEmpty() && slots.stream().allMatch(ShortTermSignalOutcomeEntity::terminal)) {
                observation.markOutcomesComplete(clock.instant());
                observationRepository.save(observation);
                return;
            }
            counts.pending += (int) slots.stream().filter(slot -> !slot.terminal()).count();
            return;
        }
        LocalDate end = due.stream()
                .map(ShortTermSignalOutcomeEntity::getTargetTradeDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate begin = observation.getRecommendationTradeDate().plusDays(1);
        MarketKLineSeries series;
        try {
            series = marketDataGateway.dailyKLineSeries(observation.getSymbol(), begin, end);
        } catch (RuntimeException exception) {
            series = MarketKLineSeries.unavailable("UNAVAILABLE_DAILY_KLINE", rootMessage(exception));
        }
        Instant attemptedAt = clock.instant();
        if (series == null || !series.complete()) {
            String source = series == null ? "UNAVAILABLE_DAILY_KLINE" : series.sourceName();
            String detail = series == null ? "日K数据源未返回结果" : series.detail();
            for (ShortTermSignalOutcomeEntity slot : due) {
                slot.markSourceUnavailable(source, detail, attemptedAt);
                counts.retryable++;
            }
            outcomeRepository.saveAll(due);
            return;
        }

        for (ShortTermSignalOutcomeEntity slot : due) {
            ShortTermHorizonEvaluation evaluation = calculator.evaluate(
                    slot.getHorizon(),
                    observation.getRecommendationTradeDate(),
                    slot.getTargetTradeDate(),
                    observation.getRecommendationPrice(),
                    series.rows(),
                    observation.costAssumptions()
            );
            Instant marketTimestamp = slot.getTargetTradeDate()
                    .atTime(DAILY_BAR_TIMESTAMP)
                    .atZone(SHANGHAI)
                    .toInstant();
            slot.applyEvaluation(evaluation, series.sourceName(), marketTimestamp, attemptedAt);
            if ("MATURED".equals(evaluation.status())) {
                counts.matured++;
            } else {
                counts.unavailable++;
            }
        }
        outcomeRepository.saveAll(due);
        if (!slots.isEmpty() && slots.stream().allMatch(ShortTermSignalOutcomeEntity::terminal)) {
            observation.markOutcomesComplete(attemptedAt);
            observationRepository.save(observation);
        } else {
            counts.pending += (int) slots.stream().filter(slot -> !slot.terminal()).count();
        }
    }

    private String rootMessage(RuntimeException exception) {
        Throwable cursor = exception;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            log.debug("Short-term outcome source failed without a message", exception);
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private static final class MutableCounts {
        private final int observations;
        private int matured;
        private int unavailable;
        private int retryable;
        private int pending;

        private MutableCounts(int observations) {
            this.observations = observations;
        }

        private ShortTermOutcomeRefreshResult snapshot() {
            return new ShortTermOutcomeRefreshResult(
                    observations, matured, unavailable, retryable, pending);
        }
    }
}

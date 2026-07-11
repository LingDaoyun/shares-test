package com.aistock.research.tradefeedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({TradeFeedbackService.class, TradeLedgerCalculator.class, TradeFeedbackConcurrencyIntegrationTest.TestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeFeedbackConcurrencyIntegrationTest {

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static final Instant RECOMMENDED_AT = Instant.parse("2026-07-13T01:00:00Z");

    @org.springframework.beans.factory.annotation.Autowired
    private TradeFeedbackService service;

    @org.springframework.beans.factory.annotation.Autowired
    private TradeCaseRepository cases;

    @org.springframework.beans.factory.annotation.Autowired
    private TradeFillRepository fills;

    @org.springframework.beans.factory.annotation.Autowired
    private TradeLedgerCalculator ledgerCalculator;

    @BeforeEach
    void clearDatabase() {
        fills.deleteAll();
        cases.deleteAll();
    }

    @Test
    void concurrentSellsNeverPersistAnOversoldLedger() throws Exception {
        TradeCaseEntity tradeCase = service.createCase(caseRequest("decision-sells"));
        service.addFill(tradeCase.getCaseId(), fill(TradeSide.BUY, "2026-07-13T01:05:00Z", "35", 100));

        List<Attempt<TradeCaseEntity>> attempts = runConcurrently(
                () -> service.addFill(tradeCase.getCaseId(), fill(TradeSide.SELL, "2026-07-13T01:10:00Z", "36", 60)),
                () -> service.addFill(tradeCase.getCaseId(), fill(TradeSide.SELL, "2026-07-13T01:10:00Z", "36", 60))
        );

        assertThat(attempts).filteredOn(attempt -> attempt.error() == null).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> attempt.error() instanceof IllegalArgumentException).hasSize(1);
        List<TradeFillEntity> persisted = fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId());
        assertThat(ledgerCalculator.calculate(persisted, null).positionQuantity()).isEqualTo(40);
        assertThat(persisted).hasSize(2);
    }

    @Test
    void concurrentDuplicateCreatesReturnTheSamePersistedCase() throws Exception {
        CreateTradeCaseRequest request = caseRequest("decision-duplicate");

        List<Attempt<TradeCaseEntity>> attempts = runConcurrently(
                () -> service.createCase(request),
                () -> service.createCase(request)
        );

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.error()).isNull());
        String caseId = attempts.get(0).value().getCaseId();
        assertThat(attempts).extracting(attempt -> attempt.value().getCaseId()).containsOnly(caseId);
        assertThat(cases.findAll()).hasSize(1);
    }

    @SafeVarargs
    private final <T> List<Attempt<T>> runConcurrently(Callable<T>... operations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        CyclicBarrier start = new CyclicBarrier(operations.length);
        try {
            List<Future<Attempt<T>>> futures = java.util.Arrays.stream(operations)
                    .map(operation -> executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            return new Attempt<>(operation.call(), null);
                        } catch (Throwable error) {
                            return new Attempt<T>(null, error);
                        }
                    }))
                    .toList();
            return futures.stream().map(this::await).toList();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private <T> Attempt<T> await(Future<Attempt<T>> future) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } catch (ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new AssertionError(error);
        }
    }

    private CreateTradeCaseRequest caseRequest(String decisionId) {
        return new CreateTradeCaseRequest(
                null,
                "002714",
                "牧原股份",
                "MISPRICING",
                "分批建仓",
                new BigDecimal("78"),
                "mispricing-v2",
                new BigDecimal("36.20"),
                RECOMMENDED_AT,
                java.util.Map.of("source", "concurrency-test")
        );
    }

    private UpsertTradeFillRequest fill(TradeSide side, String executedAt, String price, long quantity) {
        return new UpsertTradeFillRequest(side, Instant.parse(executedAt), new BigDecimal(price), quantity);
    }

    private record Attempt<T>(T value, Throwable error) {
    }
}

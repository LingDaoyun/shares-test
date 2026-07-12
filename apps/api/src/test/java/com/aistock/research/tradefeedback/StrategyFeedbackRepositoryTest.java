package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class StrategyFeedbackRepositoryTest {

    @Autowired
    private TradeCaseRepository cases;

    @Autowired
    private TradeOutcomeRepository outcomes;

    @Test
    void selectsOnlyMaturedRecommendationT20RowsWithReturns() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        List<String> caseIds = List.of(
                "included", "current", "execution", "pending", "unavailable", "null-return", "t5");
        caseIds.forEach(caseId -> cases.save(verifiedTradeCase(caseId, now)));
        cases.save(unverifiedTradeCase("unverified", now));

        outcomes.save(matured("included", "RECOMMENDATION", "T20", decimal("3.00"), now));
        outcomes.save(matured("current", "RECOMMENDATION", "CURRENT", decimal("3.00"), now));
        outcomes.save(matured("execution", "EXECUTION", "T20", decimal("3.00"), now));
        outcomes.save(TradeOutcomeEntity.pending("snapshot-pending", "pending", "RECOMMENDATION", "T20", now));
        TradeOutcomeEntity unavailable = TradeOutcomeEntity.pending(
                "snapshot-unavailable", "unavailable", "RECOMMENDATION", "T20", now);
        unavailable.replaceWith(
                new OutcomeResult("T20", null, null, null, null, null, null, "UNAVAILABLE"),
                null,
                null,
                now);
        outcomes.save(unavailable);
        outcomes.save(matured("null-return", "RECOMMENDATION", "T20", null, now));
        outcomes.save(matured("t5", "RECOMMENDATION", "T5", decimal("3.00"), now));
        outcomes.save(matured("unverified", "RECOMMENDATION", "T20", decimal("3.00"), now));
        outcomes.flush();

        assertThat(outcomes.findMaturedRecommendationT20(PageRequest.of(0, 100)))
                .extracting(MaturedRecommendationRow::caseId)
                .containsExactly("included");
    }

    private TradeCaseEntity verifiedTradeCase(String caseId, Instant now) {
        return TradeCaseEntity.verifiedPlanned(
                caseId,
                "fingerprint-" + caseId,
                "attestation-" + caseId,
                "002714",
                "牧原股份",
                "MODULE",
                "观察",
                decimal("60.00"),
                "v1",
                decimal("100.00"),
                now,
                "{}",
                now
        );
    }

    private TradeCaseEntity unverifiedTradeCase(String caseId, Instant now) {
        return TradeCaseEntity.planned(
                caseId, "fingerprint-" + caseId, null, "002714", "牧原股份", "MODULE",
                "观察", decimal("60.00"), "v1", decimal("100.00"), now, "{}", now);
    }

    private TradeOutcomeEntity matured(
            String caseId,
            String baselineType,
            String horizon,
            BigDecimal returnPct,
            Instant now
    ) {
        return TradeOutcomeEntity.matured(
                "snapshot-" + caseId,
                caseId,
                baselineType,
                horizon,
                decimal("100.00"),
                decimal("103.00"),
                LocalDate.parse("2026-07-10"),
                returnPct,
                decimal("5.00"),
                decimal("-2.00"),
                now
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

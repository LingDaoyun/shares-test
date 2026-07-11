package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TradeFeedbackPersistenceTest {

    @Autowired
    private TradeCaseRepository cases;

    @Autowired
    private TradeFillRepository fills;

    @Autowired
    private TradeOutcomeRepository outcomes;

    @Test
    void persistsOneRecommendationWithMultipleOrderedFillsAndOutcomes() {
        Instant now = Instant.parse("2026-07-11T07:00:00Z");
        cases.save(TradeCaseEntity.planned(
                "case-1", "fp-1", null, "002714", "牧原股份", "MISPRICING",
                "分批建仓", new BigDecimal("78"), "mispricing-v2",
                new BigDecimal("36.20"), now, "{}", now));
        fills.save(TradeFillEntity.create("fill-2", "case-1", "SELL", now.plusSeconds(7200),
                new BigDecimal("38.00"), 100L, now));
        fills.save(TradeFillEntity.create("fill-1", "case-1", "BUY", now.plusSeconds(3600),
                new BigDecimal("35.00"), 200L, now));
        outcomes.save(TradeOutcomeEntity.pending("outcome-1", "case-1", "RECOMMENDATION", "T5", now));

        assertThat(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1"))
                .extracting(TradeFillEntity::getSide).containsExactly("BUY", "SELL");
        assertThat(outcomes.findByCaseIdOrderByHorizonAsc("case-1")).hasSize(1);
    }
}

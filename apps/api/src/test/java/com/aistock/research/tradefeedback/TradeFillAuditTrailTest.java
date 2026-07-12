package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TradeFillAuditTrailTest {

    private static final Instant RECOMMENDED_AT = Instant.parse("2026-07-11T07:00:00Z");

    @Autowired
    private TradeCaseRepository cases;

    @Autowired
    private TradeFillRepository fills;

    @Autowired
    private TradeFillRevisionRepository revisions;

    private final TradeFillProjector projector = new TradeFillProjector();

    @Test
    void correctionKeepsTheOriginalFillAndProjectsOnlyTheLatestFacts() {
        seedCaseAndBuy();
        revisions.save(TradeFillRevisionEntity.correction(
                "revision-1", "fill-1", "case-1", "BUY",
                Instant.parse("2026-07-11T08:05:00Z"), new BigDecimal("36.20"), 200,
                Instant.parse("2026-07-11T08:10:00Z")));

        TradeFillEntity original = fills.findById("fill-1").orElseThrow();
        List<TradeFillSnapshot> active = projector.project(
                List.of(original), revisions.findByCaseIdOrderByCreatedAtAscRevisionIdAsc("case-1"));

        assertThat(original.getExecutedAt()).isEqualTo(Instant.parse("2026-07-11T08:00:00Z"));
        assertThat(original.getPrice()).isEqualByComparingTo("35.00");
        assertThat(original.getQuantity()).isEqualTo(100);
        assertThat(active).singleElement().satisfies(fill -> {
            assertThat(fill.fillId()).isEqualTo("fill-1");
            assertThat(fill.executedAt()).isEqualTo(Instant.parse("2026-07-11T08:05:00Z"));
            assertThat(fill.price()).isEqualByComparingTo("36.20");
            assertThat(fill.quantity()).isEqualTo(200);
        });
    }

    @Test
    void voidKeepsEveryAuditRowAndRemovesTheExecutionFromTheActiveProjection() {
        seedCaseAndBuy();
        revisions.save(TradeFillRevisionEntity.voided(
                "revision-void", "fill-1", "case-1", "BUY",
                Instant.parse("2026-07-11T08:00:00Z"), new BigDecimal("35.00"), 100,
                Instant.parse("2026-07-11T08:10:00Z")));

        List<TradeFillSnapshot> active = projector.project(
                fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1"),
                revisions.findByCaseIdOrderByCreatedAtAscRevisionIdAsc("case-1"));

        assertThat(active).isEmpty();
        assertThat(fills.findById("fill-1")).isPresent();
        assertThat(revisions.findAll()).singleElement()
                .extracting(TradeFillRevisionEntity::getRevisionType)
                .isEqualTo("VOID");
    }

    private void seedCaseAndBuy() {
        cases.save(TradeCaseEntity.planned(
                "case-1", "fingerprint-1", null, "002714", "牧原股份", "MISPRICING",
                "分批建仓", new BigDecimal("78"), "mispricing-v2", new BigDecimal("35.00"),
                RECOMMENDED_AT, "{}", RECOMMENDED_AT));
        fills.save(TradeFillEntity.create(
                "fill-1", "case-1", "BUY", Instant.parse("2026-07-11T08:00:00Z"),
                new BigDecimal("35.00"), 100, Instant.parse("2026-07-11T08:01:00Z")));
    }
}

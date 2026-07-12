package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

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
                "revision-1", "fill-1", "case-1", 1, "BUY",
                Instant.parse("2026-07-11T08:05:00Z"), new BigDecimal("36.20"), 200,
                Instant.parse("2026-07-11T08:10:00Z")));

        TradeFillEntity original = fills.findById("fill-1").orElseThrow();
        List<TradeFillSnapshot> active = projector.project(
                List.of(original), revisions.findByCaseIdOrderByRevisionSequenceAsc("case-1"));

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
                "revision-void", "fill-1", "case-1", 1, "BUY",
                Instant.parse("2026-07-11T08:00:00Z"), new BigDecimal("35.00"), 100,
                Instant.parse("2026-07-11T08:10:00Z")));

        List<TradeFillSnapshot> active = projector.project(
                fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1"),
                revisions.findByCaseIdOrderByRevisionSequenceAsc("case-1"));

        assertThat(active).isEmpty();
        assertThat(fills.findById("fill-1")).isPresent();
        assertThat(revisions.findAll()).singleElement()
                .extracting(TradeFillRevisionEntity::getRevisionType)
                .isEqualTo("VOID");
    }

    @Test
    void revisionSequencePreservesCausalityWhenDatabaseTimestampsTie() {
        seedCaseAndBuy();
        Instant sameTimestamp = Instant.parse("2026-07-11T08:10:00Z");
        revisions.save(TradeFillRevisionEntity.correction(
                "z-first", "fill-1", "case-1", 1, "BUY",
                Instant.parse("2026-07-11T08:05:00Z"), new BigDecimal("36.20"), 200, sameTimestamp));
        revisions.save(TradeFillRevisionEntity.correction(
                "a-second", "fill-1", "case-1", 2, "BUY",
                Instant.parse("2026-07-11T08:06:00Z"), new BigDecimal("37.10"), 150, sameTimestamp));

        List<TradeFillSnapshot> corrected = projector.project(
                fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1"),
                revisions.findByCaseIdOrderByRevisionSequenceAsc("case-1"));

        assertThat(corrected).singleElement().satisfies(fill -> {
            assertThat(fill.price()).isEqualByComparingTo("37.10");
            assertThat(fill.quantity()).isEqualTo(150);
        });

        revisions.save(TradeFillRevisionEntity.voided(
                "0-third", "fill-1", "case-1", 3, "BUY",
                Instant.parse("2026-07-11T08:06:00Z"), new BigDecimal("37.10"), 150, sameTimestamp));

        assertThat(projector.project(
                fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1"),
                revisions.findByCaseIdOrderByRevisionSequenceAsc("case-1"))).isEmpty();
    }

    @Test
    void dirtyOutcomeCasesAreSelectedBeforeOlderRoutineRefreshes() {
        for (int index = 0; index < 101; index++) {
            Instant createdAt = RECOMMENDED_AT.minusSeconds(10_000L - index);
            cases.save(TradeCaseEntity.planned(
                    "routine-" + index, "fingerprint-" + index, null,
                    "600519", "贵州茅台", "SHORT_TERM", "观察", null,
                    "short-term-v1", new BigDecimal("1500"), createdAt, "{}", createdAt));
        }
        TradeCaseEntity dirty = TradeCaseEntity.planned(
                "dirty-closed", "fingerprint-dirty", null, "002714", "牧原股份", "MISPRICING",
                "分批建仓", new BigDecimal("78"), "mispricing-v2", new BigDecimal("35.00"),
                RECOMMENDED_AT, "{}", RECOMMENDED_AT);
        dirty.updateStatusAndMarkOutcomeDirty("CLOSED", RECOMMENDED_AT.plusSeconds(10_000));
        cases.saveAndFlush(dirty);

        List<TradeCaseEntity> selected = cases.findRefreshCandidates(PageRequest.of(0, 100));

        assertThat(selected).hasSize(100);
        assertThat(selected.get(0).getCaseId()).isEqualTo("dirty-closed");
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

package com.aistock.research.v2.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class V2QuoteSnapshotServiceTest {

    @Autowired
    private V2QuoteSnapshotRepository repository;

    @Autowired
    private V2QuoteSnapshotService service;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void returnsOnlySnapshotsAvailableAtDecisionTime() {
        service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:01:00Z"),
                Instant.parse("2026-07-14T07:01:05Z"),
                "EAST_MONEY", "request-a", DataQualityStatus.VERIFIED, "{}");
        service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("36.20"), new BigDecimal("223456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:10:00Z"),
                Instant.parse("2026-07-14T07:10:05Z"),
                "EAST_MONEY", "request-b", DataQualityStatus.VERIFIED, "{}");

        Optional<V2QuoteSnapshotEntity> visible = service.latestVisible(
                "002714", QuoteStage.CLOSE_1500, Instant.parse("2026-07-14T07:05:00Z"));

        assertThat(visible).isPresent();
        assertThat(visible.get().getLastPrice()).isEqualByComparingTo(new BigDecimal("35.100000"));
        assertThat(visible.get().getAvailableAt()).isEqualTo(Instant.parse("2026-07-14T07:01:00Z"));
    }

    @Test
    void keepsConflictingSourceQualityVisibleForGates() {
        V2QuoteSnapshotEntity saved = service.record("600036", "招商银行", QuoteStage.INTRADAY,
                new BigDecimal("42.00"), new BigDecimal("323456789.00"),
                Instant.parse("2026-07-14T06:30:00Z"),
                Instant.parse("2026-07-14T06:30:03Z"),
                Instant.parse("2026-07-14T06:30:04Z"),
                "TENCENT", "request-conflict", DataQualityStatus.CONFLICT, "{\"reason\":\"price-diff\"}");

        assertThat(saved.getQualityStatus()).isEqualTo(DataQualityStatus.CONFLICT);
        assertThat(saved.getRawPayloadHash()).hasSize(64);
    }

    @Test
    void excludesSnapshotsIngestedAfterDecisionEvenWhenAvailableAtWasBackdated() {
        service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:01:00Z"),
                Instant.parse("2026-07-14T07:10:00Z"),
                "EAST_MONEY", "request-late-ingestion", DataQualityStatus.VERIFIED, "{}");

        Optional<V2QuoteSnapshotEntity> visible = service.latestVisible(
                "002714", QuoteStage.CLOSE_1500, Instant.parse("2026-07-14T07:05:00Z"));

        assertThat(visible).isEmpty();
    }

    @Test
    void rejectsSnapshotChronologyThatCannotBeReplayedSafely() {
        assertThatThrownBy(() -> service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:02:00Z"),
                Instant.parse("2026-07-14T07:01:00Z"),
                Instant.parse("2026-07-14T07:03:00Z"),
                "EAST_MONEY", "request-invalid-effective", DataQualityStatus.VERIFIED, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveAt <= availableAt <= ingestedAt");

        assertThatThrownBy(() -> service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:03:00Z"),
                Instant.parse("2026-07-14T07:02:00Z"),
                "EAST_MONEY", "request-invalid-ingested", DataQualityStatus.VERIFIED, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveAt <= availableAt <= ingestedAt");
    }

    @Test
    void preservesExistingSnapshotWhenASeparateCaptureChangesQualityFacts() {
        V2QuoteSnapshotEntity first = service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:01:00Z"),
                Instant.parse("2026-07-14T07:01:05Z"),
                "EAST_MONEY", "request-same-capture", DataQualityStatus.VERIFIED, "{\"price\":35.10}");
        V2QuoteSnapshotEntity duplicate = service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:01:00Z"),
                Instant.parse("2026-07-14T07:01:05Z"),
                "EAST_MONEY", "request-same-capture", DataQualityStatus.VERIFIED, "{\"price\":35.10}");
        V2QuoteSnapshotEntity conflict = service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:01:00Z"),
                Instant.parse("2026-07-14T07:01:05Z"),
                "EAST_MONEY", "request-same-capture", DataQualityStatus.CONFLICT, "{\"price\":35.10}");

        assertThat(duplicate.getSnapshotId()).isEqualTo(first.getSnapshotId());
        assertThat(conflict.getSnapshotId()).isNotEqualTo(first.getSnapshotId());
        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findById(first.getSnapshotId()).orElseThrow().getQualityStatus())
                .isEqualTo(DataQualityStatus.VERIFIED);
    }
}

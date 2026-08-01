package com.aistock.research.shortterm.chip;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermChipVerificationStoreTest {

    private final ShortTermChipVerificationRepository repository = mock(ShortTermChipVerificationRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ShortTermChipVerificationStore store = new ShortTermChipVerificationStore(repository, objectMapper);

    @Test
    void persistsAndReadsBySymbolDateAndModelVersion() {
        ShortTermChipSnapshot snapshot = snapshot();
        LocalDate date = LocalDate.of(2026, 7, 30);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        store.save(
                "002580", date, "short-term-chip-v1", snapshot, external(),
                Instant.parse("2026-07-30T06:50:00Z"),
                Instant.parse("2026-07-30T06:51:00Z"),
                null
        );

        ArgumentCaptor<ShortTermChipVerificationEntity> captor =
                ArgumentCaptor.forClass(ShortTermChipVerificationEntity.class);
        verify(repository).save(captor.capture());
        ShortTermChipVerificationEntity saved = captor.getValue();
        assertThat(saved.getVerificationKey()).isEqualTo("002580:2026-07-30:short-term-chip-v1");
        assertThat(saved.getStatus()).isEqualTo(ChipVerificationStatus.VERIFIED);
        when(repository.findBySymbolAndTradeDateAndModelVersion("002580", date, "short-term-chip-v1"))
                .thenReturn(Optional.of(saved));

        ShortTermChipVerificationEvidence restored = store
                .find("002580", date, "short-term-chip-v1")
                .orElseThrow();
        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.external()).isEqualTo(external());
        assertThat(store.find("002580", date, "short-term-chip-v2")).isEmpty();
    }

    @Test
    void removesTokenShapedValuesBeforePersistence() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ShortTermChipSnapshot snapshotWithSecrets = snapshot().withAdditionalGaps(java.util.List.of(
                "upstream payload: {\"token\":\"json-secret\"}",
                "Authorization: Bearer bearer-secret",
                "https://api.example.test/data?api_key=query-secret&symbol=002580"
        ));

        store.save(
                "002580", LocalDate.of(2026, 7, 30), "short-term-chip-v1",
                snapshotWithSecrets, null, Instant.now(), Instant.now(),
                "HTTP 429 token=secret-token api_key=another-secret"
        );

        ArgumentCaptor<ShortTermChipVerificationEntity> captor =
                ArgumentCaptor.forClass(ShortTermChipVerificationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getErrorSummary())
                .contains("HTTP 429")
                .doesNotContain("secret-token")
                .doesNotContain("another-secret");
        assertThat(captor.getValue().getSnapshotJson())
                .contains("[REDACTED]")
                .doesNotContain("json-secret")
                .doesNotContain("bearer-secret")
                .doesNotContain("query-secret");
    }

    @Test
    void treatsRepeatedEvidenceKeyAsAnUpdate() {
        String verificationKey = "002580:2026-07-30:short-term-chip-v1";
        when(repository.existsById(verificationKey)).thenReturn(true);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        store.save(
                "002580", LocalDate.of(2026, 7, 30), "short-term-chip-v1",
                snapshot(), external(), Instant.now(), Instant.now(), null
        );

        ArgumentCaptor<ShortTermChipVerificationEntity> captor =
                ArgumentCaptor.forClass(ShortTermChipVerificationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isNew()).isFalse();
    }

    private ShortTermChipSnapshot snapshot() {
        ChipVerificationResult verification = new ChipVerificationResult(
                ChipVerificationStatus.VERIFIED,
                java.math.BigDecimal.ONE,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ONE,
                java.math.BigDecimal.ZERO,
                java.util.List.of()
        );
        return new ChipStructureScorer(new java.math.BigDecimal("0.25"))
                .score(ChipTestFixtures.localDistribution(), verification, external());
    }

    private ExternalChipPerformance external() {
        return new ExternalChipPerformance(
                "002580", LocalDate.of(2026, 7, 30),
                bd("9.00"), bd("9.50"), bd("10.00"), bd("10.50"), bd("11.00"),
                bd("10.00"), bd("60"), "Tushare cyq_perf", Instant.parse("2026-07-30T10:00:00Z")
        );
    }

    private java.math.BigDecimal bd(String value) {
        return new java.math.BigDecimal(value);
    }
}

package com.aistock.research.history;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KlineHistoryRecorderTest {

    private final KlineHistoryRepository repository = mock(KlineHistoryRepository.class);
    private final KlineHistoryRecorder recorder = new KlineHistoryRecorder(repository);

    @Test
    void stableBarsUseDeterministicIdsWhileCorrectedBarsKeepAnotherVersion() {
        EastMoneyKLine original = kline("10.20");
        EastMoneyKLine duplicate = kline("10.20");
        EastMoneyKLine corrected = kline("10.25");

        recorder.record(List.of(original, duplicate, corrected), "腾讯前复权日线");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<KlineHistoryEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        List<KlineHistoryEntity> entities = StreamSupport.stream(captor.getValue().spliterator(), false).toList();
        assertThat(entities).hasSize(2);
        assertThat(entities).extracting(KlineHistoryEntity::getObservationId).doesNotHaveDuplicates();
        assertThat(entities).extracting(KlineHistoryEntity::getClose)
                .containsExactlyInAnyOrder(new BigDecimal("10.20"), new BigDecimal("10.25"));
    }

    @Test
    void skipsBarsWhoseContentFingerprintWasAlreadyArchived() {
        EastMoneyKLine row = kline("10.20");
        recorder.record(List.of(row), "腾讯前复权日线");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<KlineHistoryEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        String archivedId = captor.getValue().iterator().next().getObservationId();
        reset(repository);
        when(repository.findObservationIdsBySymbolAndTradeDateBetween(
                "600036", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-10")
        )).thenReturn(List.of(archivedId));

        recorder.record(List.of(row), "腾讯前复权日线");

        verify(repository, never()).saveAll(any());
    }

    @Test
    void persistsTurnoverRateWithArchivedBar() {
        EastMoneyKLine row = new EastMoneyKLine(
                "600036",
                LocalDate.parse("2026-07-10"),
                new BigDecimal("10.00"),
                new BigDecimal("10.20"),
                new BigDecimal("10.40"),
                new BigDecimal("9.90"),
                new BigDecimal("100000"),
                new BigDecimal("1020000"),
                new BigDecimal("3.42")
        );

        recorder.record(List.of(row), "东方财富前复权日线");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<KlineHistoryEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        KlineHistoryEntity saved = captor.getValue().iterator().next();
        assertThat(saved.getTurnoverRate()).isEqualByComparingTo("3.42");
    }

    private EastMoneyKLine kline(String close) {
        return new EastMoneyKLine(
                "600036",
                LocalDate.parse("2026-07-10"),
                new BigDecimal("10.00"),
                new BigDecimal(close),
                new BigDecimal("10.40"),
                new BigDecimal("9.90"),
                new BigDecimal("100000"),
                new BigDecimal("1020000")
        );
    }
}

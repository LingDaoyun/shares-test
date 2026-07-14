package com.aistock.research.v2.data;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface V2QuoteSnapshotRepository extends JpaRepository<V2QuoteSnapshotEntity, String> {

    Optional<V2QuoteSnapshotEntity> findFirstBySymbolAndQuoteStageAndAvailableAtLessThanEqualAndIngestedAtLessThanEqualOrderByAvailableAtDescIngestedAtDescSnapshotIdDesc(
            String symbol,
            QuoteStage quoteStage,
            Instant availableAtCutoff,
            Instant ingestedAtCutoff
    );
}

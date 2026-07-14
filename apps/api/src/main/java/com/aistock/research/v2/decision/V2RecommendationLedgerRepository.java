package com.aistock.research.v2.decision;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface V2RecommendationLedgerRepository extends JpaRepository<V2RecommendationLedgerEntity, String> {

    Optional<V2RecommendationLedgerEntity> findByRecommendationFingerprint(String recommendationFingerprint);

    Optional<V2RecommendationLedgerEntity> findFirstBySymbolAndSignalProvenanceNotOrderByDecisionAtDescLedgerIdDesc(
            String symbol,
            String excludedProvenance
    );
}

package com.aistock.research.v2.factor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface V2FactorSnapshotRepository extends JpaRepository<V2FactorSnapshotEntity, String> {

    List<V2FactorSnapshotEntity> findBySymbolAndStrategyCodeAndStrategyVersionAndAvailableAtLessThanEqualAndCalculatedAtLessThanEqualOrderByAvailableAtDescCalculatedAtDesc(
            String symbol,
            String strategyCode,
            String strategyVersion,
            Instant availableAtCutoff,
            Instant calculatedAtCutoff
    );
}

package com.aistock.research.tradefeedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeOutcomeRepository extends JpaRepository<TradeOutcomeEntity, String> {

    List<TradeOutcomeEntity> findByCaseIdOrderByHorizonAsc(String caseId);

    Optional<TradeOutcomeEntity> findByCaseIdAndBaselineTypeAndHorizon(String caseId, String baselineType, String horizon);
}

package com.aistock.research.tradefeedback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeOutcomeRepository extends JpaRepository<TradeOutcomeEntity, String> {

    @Query("""
            select new com.aistock.research.tradefeedback.MaturedRecommendationRow(
                tradeCase.caseId,
                tradeCase.sourceModule,
                tradeCase.ruleVersion,
                tradeCase.recommendedPrice,
                tradeCase.recommendedAt,
                outcome.returnPct,
                outcome.maxRunupPct,
                outcome.maxDrawdownPct
            )
            from TradeOutcomeEntity outcome, TradeCaseEntity tradeCase
            where tradeCase.caseId = outcome.caseId
              and tradeCase.recommendationVerified = true
              and outcome.baselineType = 'RECOMMENDATION'
              and outcome.horizon = 'T20'
              and outcome.status = 'MATURED'
              and outcome.returnPct is not null
            order by tradeCase.recommendedAt desc, tradeCase.caseId desc
            """)
    List<MaturedRecommendationRow> findMaturedRecommendationT20(Pageable pageable);

    List<TradeOutcomeEntity> findByCaseIdOrderByHorizonAsc(String caseId);

    List<TradeOutcomeEntity> findByCaseIdInOrderByCaseIdAscBaselineTypeAscHorizonAsc(Collection<String> caseIds);

    Optional<TradeOutcomeEntity> findByCaseIdAndBaselineTypeAndHorizon(String caseId, String baselineType, String horizon);

    void deleteByCaseId(String caseId);
}

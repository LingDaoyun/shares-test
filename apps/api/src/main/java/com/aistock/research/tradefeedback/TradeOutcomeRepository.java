package com.aistock.research.tradefeedback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
              and outcome.baselineType = 'RECOMMENDATION'
              and outcome.horizon = 'T20'
              and outcome.status = 'MATURED'
              and outcome.returnPct is not null
            order by tradeCase.sourceModule, tradeCase.ruleVersion, tradeCase.caseId
            """)
    List<MaturedRecommendationRow> findMaturedRecommendationT20();

    List<TradeOutcomeEntity> findByCaseIdOrderByHorizonAsc(String caseId);

    List<TradeOutcomeEntity> findByCaseIdInOrderByCaseIdAscBaselineTypeAscHorizonAsc(Collection<String> caseIds);

    Optional<TradeOutcomeEntity> findByCaseIdAndBaselineTypeAndHorizon(String caseId, String baselineType, String horizon);
}

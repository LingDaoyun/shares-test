package com.aistock.research.tradefeedback;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeCaseRepository extends JpaRepository<TradeCaseEntity, String>, TradeCaseQueryRepository {

    Optional<TradeCaseEntity> findByRecommendationFingerprint(String recommendationFingerprint);

    @Query("""
            select tradeCase
            from TradeCaseEntity tradeCase
            where tradeCase.status <> 'CANCELLED'
            order by case when tradeCase.outcomeDirty = true then 0 else 1 end,
                     tradeCase.updatedAt asc,
                     tradeCase.caseId asc
            """)
    List<TradeCaseEntity> findRefreshCandidates(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tradeCase from TradeCaseEntity tradeCase where tradeCase.caseId = :caseId")
    Optional<TradeCaseEntity> findByIdForUpdate(@Param("caseId") String caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select tradeCase
            from TradeCaseEntity tradeCase
            where tradeCase.symbol = :symbol
              and tradeCase.status in :statuses
            order by tradeCase.updatedAt desc, tradeCase.caseId asc
            """)
    List<TradeCaseEntity> findOpenCasesBySymbolForUpdate(
            @Param("symbol") String symbol,
            @Param("statuses") Collection<String> statuses,
            Pageable pageable
    );
}

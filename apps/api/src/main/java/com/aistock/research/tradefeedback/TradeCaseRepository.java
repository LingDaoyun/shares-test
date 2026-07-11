package com.aistock.research.tradefeedback;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TradeCaseRepository extends JpaRepository<TradeCaseEntity, String> {

    List<TradeCaseEntity> findAllByOrderByCreatedAtDesc();

    Optional<TradeCaseEntity> findByRecommendationFingerprint(String recommendationFingerprint);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tradeCase from TradeCaseEntity tradeCase where tradeCase.caseId = :caseId")
    Optional<TradeCaseEntity> findByIdForUpdate(@Param("caseId") String caseId);
}

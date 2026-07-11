package com.aistock.research.tradefeedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeCaseRepository extends JpaRepository<TradeCaseEntity, String> {

    List<TradeCaseEntity> findAllByOrderByCreatedAtDesc();

    Optional<TradeCaseEntity> findByRecommendationFingerprint(String recommendationFingerprint);
}

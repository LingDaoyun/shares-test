package com.aistock.research.ai;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TrendAnalysisRunRepository extends JpaRepository<TrendAnalysisRunEntity, Long> {

    Optional<TrendAnalysisRunEntity> findByAnalysisDateAndRequestFingerprint(LocalDate analysisDate, String requestFingerprint);

    List<TrendAnalysisRunEntity> findAllByOrderByAnalyzedAtDesc(Pageable pageable);
}

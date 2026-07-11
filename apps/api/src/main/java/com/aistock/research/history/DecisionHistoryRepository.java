package com.aistock.research.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionHistoryRepository extends JpaRepository<DecisionHistoryEntity, String> {

    List<DecisionHistoryEntity> findTop50BySymbolOrderByRecordedAtDesc(String symbol);
}

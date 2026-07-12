package com.aistock.research.tradefeedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TradeFillRepository extends JpaRepository<TradeFillEntity, String> {

    List<TradeFillEntity> findByCaseIdInAndSideOrderByExecutedAtAscCreatedAtAscFillIdAsc(
            Collection<String> caseIds,
            String side
    );

    List<TradeFillEntity> findByCaseIdOrderByExecutedAtAscCreatedAtAsc(String caseId);
}

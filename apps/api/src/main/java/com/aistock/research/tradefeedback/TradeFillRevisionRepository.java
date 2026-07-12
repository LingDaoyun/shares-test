package com.aistock.research.tradefeedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TradeFillRevisionRepository extends JpaRepository<TradeFillRevisionEntity, String> {

    List<TradeFillRevisionEntity> findByCaseIdOrderByCreatedAtAscRevisionIdAsc(String caseId);

    List<TradeFillRevisionEntity> findByCaseIdInOrderByCaseIdAscCreatedAtAscRevisionIdAsc(Collection<String> caseIds);
}

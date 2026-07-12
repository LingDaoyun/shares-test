package com.aistock.research.tradefeedback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TradeFillRevisionRepository extends JpaRepository<TradeFillRevisionEntity, String> {

    List<TradeFillRevisionEntity> findByCaseIdOrderByRevisionSequenceAsc(String caseId);

    List<TradeFillRevisionEntity> findByCaseIdInOrderByCaseIdAscRevisionSequenceAsc(Collection<String> caseIds);

    @Query("select coalesce(max(revision.revisionSequence), 0) from TradeFillRevisionEntity revision where revision.caseId = :caseId")
    long findMaxRevisionSequenceByCaseId(@Param("caseId") String caseId);
}

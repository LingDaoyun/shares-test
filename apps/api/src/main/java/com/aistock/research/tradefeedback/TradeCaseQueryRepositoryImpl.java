package com.aistock.research.tradefeedback;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TradeCaseQueryRepositoryImpl implements TradeCaseQueryRepository {

    private final EntityManager entityManager;

    public TradeCaseQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<TradeCaseEntity> findCasePage(
            String status,
            String symbol,
            Instant beforeCreatedAt,
            String beforeCaseId,
            int limit
    ) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TradeCaseEntity> query = builder.createQuery(TradeCaseEntity.class);
        Root<TradeCaseEntity> tradeCase = query.from(TradeCaseEntity.class);
        List<Predicate> predicates = new ArrayList<>();
        if (status != null) {
            predicates.add(builder.equal(tradeCase.get("status"), status));
        }
        if (symbol != null) {
            predicates.add(builder.equal(tradeCase.get("symbol"), symbol));
        }
        if (beforeCreatedAt != null) {
            predicates.add(builder.or(
                    builder.lessThan(tradeCase.get("createdAt"), beforeCreatedAt),
                    builder.and(
                            builder.equal(tradeCase.get("createdAt"), beforeCreatedAt),
                            builder.lessThan(tradeCase.get("caseId"), beforeCaseId))));
        }
        query.where(predicates.toArray(Predicate[]::new));
        query.orderBy(builder.desc(tradeCase.get("createdAt")), builder.desc(tradeCase.get("caseId")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}

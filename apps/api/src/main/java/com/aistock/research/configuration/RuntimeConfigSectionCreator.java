package com.aistock.research.configuration;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class RuntimeConfigSectionCreator {

    private final RuntimeConfigSectionRepository repository;
    private final EntityManager entityManager;

    public RuntimeConfigSectionCreator(
            RuntimeConfigSectionRepository repository,
            EntityManager entityManager
    ) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertIfMissing(RuntimeConfigSectionKey key, String payload) {
        if (repository.existsById(key.name())) {
            return;
        }
        entityManager.persist(new RuntimeConfigSectionEntity(
                key.name(),
                payload,
                0,
                Instant.now()
        ));
        entityManager.flush();
    }
}

package com.aistock.research.configuration;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RuntimeConfigSectionRepository
        extends JpaRepository<RuntimeConfigSectionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select section from RuntimeConfigSectionEntity section "
            + "where section.sectionKey = :sectionKey")
    Optional<RuntimeConfigSectionEntity> findForUpdate(@Param("sectionKey") String sectionKey);
}

package com.aistock.research.shortterm.validation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShortTermSignalObservationRepository
        extends JpaRepository<ShortTermSignalObservationEntity, String> {

    List<ShortTermSignalObservationEntity>
    findByValidationEligibleTrueAndOutcomeStateOrderByPublishedAtAsc(
            String outcomeState,
            Pageable pageable
    );
}

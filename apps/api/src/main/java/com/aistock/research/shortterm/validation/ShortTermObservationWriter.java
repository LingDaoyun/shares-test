package com.aistock.research.shortterm.validation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ShortTermObservationWriter {

    private final ShortTermSignalObservationRepository observationRepository;
    private final ShortTermSignalOutcomeRepository outcomeRepository;

    public ShortTermObservationWriter(
            ShortTermSignalObservationRepository observationRepository,
            ShortTermSignalOutcomeRepository outcomeRepository
    ) {
        this.observationRepository = observationRepository;
        this.outcomeRepository = outcomeRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean persistIfAbsent(
            ShortTermSignalObservationEntity observation,
            List<ShortTermSignalOutcomeEntity> outcomes
    ) {
        if (observationRepository.existsById(observation.getObservationId())) {
            return false;
        }
        observationRepository.saveAndFlush(observation);
        if (outcomes != null && !outcomes.isEmpty()) {
            outcomeRepository.saveAllAndFlush(outcomes);
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean exists(String observationId) {
        return observationRepository.existsById(observationId);
    }
}

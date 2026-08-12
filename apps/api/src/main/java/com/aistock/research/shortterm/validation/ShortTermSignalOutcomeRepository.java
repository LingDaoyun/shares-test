package com.aistock.research.shortterm.validation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShortTermSignalOutcomeRepository
        extends JpaRepository<ShortTermSignalOutcomeEntity, String> {

    List<ShortTermSignalOutcomeEntity> findByObservationIdOrderByHorizonAsc(String observationId);

    Optional<ShortTermSignalOutcomeEntity> findByObservationIdAndHorizon(
            String observationId,
            String horizon
    );

    @Query("""
            select new com.aistock.research.shortterm.validation.ShortTermValidationSample(
                outcome.netReturnPercent,
                outcome.maxFavorableExcursionPercent,
                outcome.maxAdverseExcursionPercent
            )
            from ShortTermSignalOutcomeEntity outcome, ShortTermSignalObservationEntity observation
            where outcome.observationId = observation.observationId
              and observation.calibrationEligible = true
              and observation.strategyVersion = :strategyVersion
              and observation.signalFamily = :signalFamily
              and observation.marketRegime = :marketRegime
              and outcome.horizon = :horizon
              and outcome.status = 'MATURED'
              and outcome.netReturnPercent is not null
            """)
    List<ShortTermValidationSample> findMaturedCohortSamples(
            @Param("strategyVersion") String strategyVersion,
            @Param("signalFamily") String signalFamily,
            @Param("marketRegime") String marketRegime,
            @Param("horizon") String horizon
    );
}

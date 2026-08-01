package com.aistock.research.shortterm.chip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ShortTermChipVerificationRepository
        extends JpaRepository<ShortTermChipVerificationEntity, String> {

    Optional<ShortTermChipVerificationEntity> findBySymbolAndTradeDateAndModelVersion(
            String symbol,
            LocalDate tradeDate,
            String modelVersion
    );
}

package com.aistock.research.shortterm.validation;

import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermValidationConcurrencyGuardTest {

    @Test
    void outcomeAndObservationEntitiesUseOptimisticVersions() {
        assertThat(hasVersionField(ShortTermSignalObservationEntity.class)).isTrue();
        assertThat(hasVersionField(ShortTermSignalOutcomeEntity.class)).isTrue();
    }

    private boolean hasVersionField(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .anyMatch(field -> field.isAnnotationPresent(Version.class));
    }
}

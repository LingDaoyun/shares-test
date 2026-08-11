package com.aistock.research.shortterm;

import com.aistock.research.shortterm.schedule.ShortTermScanScheduler;
import com.aistock.research.shortterm.schedule.ShortTermScheduledExecutorConfig;
import com.aistock.research.shortterm.schedule.ShortTermScheduledScanService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermScheduledSnapshotControllerTest {

    @Test
    void shortTermControllerNoLongerExposesScheduledSnapshotEndpoint() {
        boolean exposesScheduledSnapshotEndpoint = Arrays.stream(ShortTermController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotationsByType(GetMapping.class)))
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch("/scheduled-snapshots/latest"::equals);

        assertThat(exposesScheduledSnapshotEndpoint).isFalse();
    }

    @Test
    void shortTermScheduledAutomationIsNotSpringManaged() {
        assertThat(ShortTermScanScheduler.class.isAnnotationPresent(Component.class)).isFalse();
        assertThat(ShortTermScheduledScanService.class.isAnnotationPresent(Service.class)).isFalse();
        assertThat(ShortTermScheduledExecutorConfig.class.isAnnotationPresent(Configuration.class)).isFalse();
    }
}

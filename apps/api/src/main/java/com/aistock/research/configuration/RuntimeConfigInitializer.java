package com.aistock.research.configuration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RuntimeConfigInitializer implements ApplicationRunner {

    private final RuntimeConfigStore store;

    public RuntimeConfigInitializer(RuntimeConfigStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        store.initializeMissingSections();
    }
}

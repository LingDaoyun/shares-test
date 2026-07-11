package com.aistock.research.configuration;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime-config")
public class RuntimeConfigController {

    private final RuntimeConfigService runtimeConfigService;

    public RuntimeConfigController(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping
    public RuntimeConfigSnapshot currentConfig() {
        return runtimeConfigService.currentConfig();
    }

    @PutMapping
    public RuntimeConfigSnapshot updateConfig(@Valid @RequestBody RuntimeConfigSnapshot request) {
        return runtimeConfigService.updateConfig(request);
    }
}

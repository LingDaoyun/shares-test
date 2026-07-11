package com.aistock.research.rule;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleCatalogService catalogService;
    private final RuleEngineService engineService;

    public RuleController(RuleCatalogService catalogService, RuleEngineService engineService) {
        this.catalogService = catalogService;
        this.engineService = engineService;
    }

    @GetMapping
    public List<RuleDefinition> listRules() {
        return catalogService.listRules();
    }

    @PutMapping("/{ruleCode}")
    public RuleDefinition upsertRule(@PathVariable String ruleCode, @Valid @RequestBody RuleDefinition definition) {
        return catalogService.upsert(ruleCode, definition);
    }

    @PostMapping("/evaluate")
    public List<RuleEvaluationResult> evaluate(@Valid @RequestBody FactorSnapshot snapshot) {
        return catalogService.evaluateAll(snapshot, engineService);
    }
}


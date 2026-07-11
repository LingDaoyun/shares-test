package com.aistock.research.rule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record RuleDefinition(
        @NotBlank String ruleCode,
        @NotBlank String name,
        boolean enabled,
        int version,
        @NotEmpty List<@Valid RuleCondition> conditions,
        @NotNull RuleAction action,
        String description,
        Instant updatedAt
) {
    public RuleDefinition withVersion(int nextVersion) {
        return new RuleDefinition(ruleCode, name, enabled, nextVersion, conditions, action, description, Instant.now());
    }
}


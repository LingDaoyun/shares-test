package com.aistock.research.configuration;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuntimeConfigService {

    private static final String STORAGE = "database";

    private final RuntimeConfigStore store;

    public RuntimeConfigService(RuntimeConfigStore store) {
        this.store = store;
    }

    public RuntimeConfigSnapshot currentConfig() {
        return snapshot(store.readState());
    }

    public LlmRuntimeConfig currentLlmConfig() {
        return publicLlm(store.readLlm());
    }

    public List<PolicySourceConfig> currentPolicySources() {
        return store.readPolicySources();
    }

    public LlmRuntimeConfig updateLlmConfig(LlmRuntimeConfig request) {
        return publicLlm(store.updateLlm(request));
    }

    public List<PolicySourceConfig> updatePolicySources(List<PolicySourceConfig> request) {
        return store.updatePolicySources(request);
    }

    public RuntimeConfigSnapshot updateConfig(RuntimeConfigSnapshot request) {
        RuntimeConfigState state = store.updateAll(request.llm(), request.policySources());
        return snapshot(state);
    }

    private RuntimeConfigSnapshot snapshot(RuntimeConfigState state) {
        return new RuntimeConfigSnapshot(
                STORAGE,
                state.llmRevision(),
                state.policySourcesRevision(),
                publicLlm(state.llm()),
                state.policySources(),
                state.updatedAt()
        );
    }

    private LlmRuntimeConfig publicLlm(StoredLlmConfig stored) {
        boolean keyConfigured = hasText(stored.apiKey());
        return new LlmRuntimeConfig(
                stored.provider(),
                null,
                stored.apiKeyEnv(),
                stored.model(),
                stored.baseUrl(),
                stored.responseFormat(),
                stored.strictJsonSchema(),
                stored.thinking(),
                stored.maxCompletionTokens(),
                stored.temperature(),
                keyConfigured,
                keyConfigured ? "database" : "missing"
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

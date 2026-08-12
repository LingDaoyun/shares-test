package com.aistock.research.configuration;

import com.aistock.research.ai.LlmConfigPreview;
import com.aistock.research.ai.LlmSettingsProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuntimeConfigService {

    private static final String STORAGE = "database";

    private final RuntimeConfigStore store;
    private final LlmSettingsProvider settingsProvider;

    public RuntimeConfigService(RuntimeConfigStore store, LlmSettingsProvider settingsProvider) {
        this.store = store;
        this.settingsProvider = settingsProvider;
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
        RuntimeConfigState state = store.updateAll(
                request.llm(),
                request.policySources(),
                request.llmRevision(),
                request.policySourcesRevision()
        );
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
        LlmConfigPreview effective = settingsProvider.preview(stored);
        return new LlmRuntimeConfig(
                effective.provider(),
                null,
                stored.apiKeyEnv(),
                effective.model(),
                effective.baseUrl(),
                effective.responseFormat(),
                effective.strictJsonSchema(),
                effective.thinking(),
                effective.maxCompletionTokens(),
                effective.temperature(),
                effective.apiKeyConfigured(),
                effective.apiKeySource()
        );
    }
}

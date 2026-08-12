package com.aistock.research.configuration;

import com.aistock.research.ai.LlmSettingsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceTest {

    private RuntimeConfigStore store;
    private MockEnvironment environment;
    private RuntimeConfigService service;

    @BeforeEach
    void setUp() {
        store = mock(RuntimeConfigStore.class);
        environment = new MockEnvironment();
        service = new RuntimeConfigService(store, new LlmSettingsProvider(store, environment));
    }

    @Test
    void currentConfigReportsAnEnvironmentKeyWithoutReturningItsValue() {
        environment.setProperty("DEEPSEEK_API_KEY", "environment-key");
        when(store.readState()).thenReturn(state(null, "existing-model", 7, 3));

        RuntimeConfigSnapshot response = service.currentConfig();

        assertThat(response.llm().apiKey()).isNull();
        assertThat(response.llm().apiKeyConfigured()).isTrue();
        assertThat(response.llm().apiKeySource()).isEqualTo("env:DEEPSEEK_API_KEY");
    }

    @Test
    void currentConfigReturnsDatabaseMetadataAndNeverExposesApiKey() {
        when(store.readState()).thenReturn(state("test-secret", "existing-model", 7, 3));

        RuntimeConfigSnapshot response = service.currentConfig();

        assertThat(response.storage()).isEqualTo("database");
        assertThat(response.llmRevision()).isEqualTo(7);
        assertThat(response.policySourcesRevision()).isEqualTo(3);
        assertThat(response.llm().model()).isEqualTo("existing-model");
        assertThat(response.llm().apiKey()).isNull();
        assertThat(response.llm().apiKeyConfigured()).isTrue();
        assertThat(response.llm().apiKeySource()).isEqualTo("database");
    }

    @Test
    void updateLlmReturnsDatabaseResultWithoutApiKey() {
        when(store.updateLlm(any())).thenReturn(stored("test-secret", "new-model"));

        LlmRuntimeConfig response = service.updateLlmConfig(request(null, "new-model"));

        assertThat(response.model()).isEqualTo("new-model");
        assertThat(response.apiKey()).isNull();
        assertThat(response.apiKeyConfigured()).isTrue();
        assertThat(response.apiKeySource()).isEqualTo("database");
        verify(store).updateLlm(any());
    }

    @Test
    void updatePolicySourcesReturnsTheCommittedSection() {
        List<PolicySourceConfig> sources = List.of(
                new PolicySourceConfig("新政策源", "json", "https://example.test/policy.json", 88)
        );
        when(store.updatePolicySources(sources)).thenReturn(sources);

        assertThat(service.updatePolicySources(sources)).isEqualTo(sources);

        verify(store).updatePolicySources(sources);
    }

    @Test
    void fullUpdateUsesOneStoreTransactionAndReturnsSanitizedState() {
        RuntimeConfigSnapshot request = new RuntimeConfigSnapshot(
                null,
                0,
                0,
                request("replacement-key", "new-model"),
                List.of(new PolicySourceConfig(
                        "新政策源", "html", "https://example.test/policy", 80)),
                null
        );
        RuntimeConfigState committed = state("replacement-key", "new-model", 8, 4);
        when(store.updateAll(request.llm(), request.policySources())).thenReturn(committed);

        RuntimeConfigSnapshot response = service.updateConfig(request);

        assertThat(response.llm().apiKey()).isNull();
        assertThat(response.llmRevision()).isEqualTo(8);
        assertThat(response.policySourcesRevision()).isEqualTo(4);
        verify(store).updateAll(request.llm(), request.policySources());
    }

    private RuntimeConfigState state(
            String key,
            String model,
            long llmRevision,
            long policyRevision
    ) {
        return new RuntimeConfigState(
                stored(key, model),
                llmRevision,
                List.of(new PolicySourceConfig(
                        "现有政策源", "json", "https://example.test/policy.json", 90)),
                policyRevision,
                Instant.parse("2026-08-13T00:00:00Z")
        );
    }

    private StoredLlmConfig stored(String key, String model) {
        return new StoredLlmConfig(
                "deepseek",
                key,
                "DEEPSEEK_API_KEY",
                model,
                "https://api.deepseek.com",
                "json_object",
                false,
                null,
                8192,
                null
        );
    }

    private LlmRuntimeConfig request(String key, String model) {
        return new LlmRuntimeConfig(
                "deepseek",
                key,
                "DEEPSEEK_API_KEY",
                model,
                "https://api.deepseek.com",
                "json_object",
                false,
                null,
                8192,
                null,
                false,
                "missing"
        );
    }
}

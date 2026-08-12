package com.aistock.research.ai;

import com.aistock.research.configuration.RuntimeConfigStore;
import com.aistock.research.configuration.StoredLlmConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmSettingsProviderTest {

    private RuntimeConfigStore store;
    private MockEnvironment environment;
    private LlmSettingsProvider provider;

    @BeforeEach
    void setUp() {
        store = mock(RuntimeConfigStore.class);
        environment = new MockEnvironment();
        provider = new LlmSettingsProvider(store, environment);
    }

    @Test
    void readsTheNewDatabaseModelOnTheNextCall() {
        when(store.readLlm())
                .thenReturn(storedModel(null, "model-a"))
                .thenReturn(storedModel(null, "model-b"));

        assertThat(provider.current().model()).isEqualTo("model-a");
        assertThat(provider.current().model()).isEqualTo("model-b");
    }

    @Test
    void directDatabaseKeyWinsButSettingsToStringNeverReportsIt() {
        environment.setProperty("DEEPSEEK_API_KEY", "environment-key");
        when(store.readLlm()).thenReturn(storedModel("test-secret", "deepseek-chat"));

        LlmSettings settings = provider.current();

        assertThat(settings.apiKey()).isEqualTo("test-secret");
        assertThat(settings.apiKeySource()).isEqualTo("database");
        assertThat(settings.toString()).doesNotContain("test-secret");
    }

    @Test
    void resolvesConfiguredEnvironmentVariableBeforeProviderDefault() {
        environment.setProperty("CUSTOM_MODEL_KEY", "configured-key");
        environment.setProperty("DEEPSEEK_API_KEY", "default-key");
        when(store.readLlm()).thenReturn(new StoredLlmConfig(
                "deepseek", null, "CUSTOM_MODEL_KEY", "deepseek-chat",
                "https://api.deepseek.com", "json_object", false,
                null, 8192, null));

        LlmSettings settings = provider.current();

        assertThat(settings.apiKey()).isEqualTo("configured-key");
        assertThat(settings.apiKeySource()).isEqualTo("env:CUSTOM_MODEL_KEY");
    }

    @Test
    void usesProviderEnvironmentDefaultWhenConfiguredNameIsMissing() {
        environment.setProperty("MOONSHOT_API_KEY", "moonshot-key");
        when(store.readLlm()).thenReturn(new StoredLlmConfig(
                "kimi", null, "MISSING_CUSTOM_KEY", null,
                null, null, true, null, null, null));

        LlmSettings settings = provider.current();

        assertThat(settings.provider()).isEqualTo("moonshot");
        assertThat(settings.apiKeySource()).isEqualTo("env:MOONSHOT_API_KEY");
        assertThat(settings.model()).isEqualTo("kimi-k2.6");
        assertThat(settings.baseUrl()).isEqualTo("https://api.moonshot.ai/v1");
        assertThat(settings.responseFormat()).isEqualTo("json_schema");
    }

    @Test
    void resolvesKimiCodeAndDeepSeekDefaultsAndNormalizesResponseFormat() {
        when(store.readLlm())
                .thenReturn(new StoredLlmConfig(
                        "kimi_code", null, null, null, null,
                        "JSON-SCHEMA", true, null, null, null))
                .thenReturn(new StoredLlmConfig(
                        "deep-seek", null, null, null, null,
                        null, false, null, null, null));

        LlmSettings kimiCode = provider.current();
        LlmSettings deepSeek = provider.current();

        assertThat(kimiCode.provider()).isEqualTo("kimi-code");
        assertThat(kimiCode.model()).isEqualTo("kimi-for-coding");
        assertThat(kimiCode.baseUrl()).isEqualTo("https://api.kimi.com/coding/v1");
        assertThat(kimiCode.responseFormat()).isEqualTo("json_schema");
        assertThat(deepSeek.provider()).isEqualTo("deepseek");
        assertThat(deepSeek.model()).isEqualTo("deepseek-v4-pro");
        assertThat(deepSeek.responseFormat()).isEqualTo("json_object");
        assertThat(deepSeek.maxCompletionTokens()).isEqualTo(8192);
    }

    @Test
    void reportsMissingWithoutInventingASecret() {
        when(store.readLlm()).thenReturn(storedModel(null, "deepseek-chat"));

        LlmSettings settings = provider.current();

        assertThat(settings.apiKey()).isNull();
        assertThat(settings.apiKeySource()).isEqualTo("missing");
    }

    private StoredLlmConfig storedModel(String key, String model) {
        return new StoredLlmConfig(
                "deepseek", key, "DEEPSEEK_API_KEY", model,
                "https://api.deepseek.com", "json_object", false,
                null, 8192, null);
    }
}

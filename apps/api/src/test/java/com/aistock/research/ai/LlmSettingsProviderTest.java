package com.aistock.research.ai;

import com.aistock.research.configuration.RuntimeConfigStore;
import com.aistock.research.configuration.LlmProviderPolicy;
import com.aistock.research.configuration.StoredLlmConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmSettingsProviderTest {

    private RuntimeConfigStore store;
    private LlmApiKeyEnvironment environment;
    private LlmSettingsProvider provider;

    @BeforeEach
    void setUp() {
        store = mock(RuntimeConfigStore.class);
        environment = mock(LlmApiKeyEnvironment.class);
        provider = new LlmSettingsProvider(store, environment, new LlmProviderPolicy());
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
        when(environment.value("DEEPSEEK_API_KEY")).thenReturn("environment-key");
        when(store.readLlm()).thenReturn(storedModel("test-secret", "deepseek-chat"));

        LlmSettings settings = provider.current();

        assertThat(settings.apiKey()).isEqualTo("test-secret");
        assertThat(settings.apiKeySource()).isEqualTo("database");
        assertThat(settings.toString()).doesNotContain("test-secret");
    }

    @Test
    void resolvesTheProvidersAllowedEnvironmentVariable() {
        when(environment.value("DEEPSEEK_API_KEY")).thenReturn("configured-key");
        when(store.readLlm()).thenReturn(new StoredLlmConfig(
                "deepseek", null, "DEEPSEEK_API_KEY", "deepseek-chat",
                "https://api.deepseek.com", "json_object", false,
                null, 8192, null));

        LlmSettings settings = provider.current();

        assertThat(settings.apiKey()).isEqualTo("configured-key");
        assertThat(settings.apiKeySource()).isEqualTo("env:DEEPSEEK_API_KEY");
    }

    @Test
    void neverTreatsAnArbitrarySpringPropertyAsAnApiKey() {
        when(store.readLlm()).thenReturn(new StoredLlmConfig(
                "deepseek", null, "spring.datasource.password", "deepseek-chat",
                "https://api.deepseek.com", "json_object", false,
                null, 8192, null));

        assertThatThrownBy(provider::current)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环境变量");
        verifyNoInteractions(environment);
    }

    @Test
    void usesProviderEnvironmentDefaultWhenTheStoredNameIsBlank() {
        when(environment.value("MOONSHOT_API_KEY")).thenReturn("moonshot-key");
        when(store.readLlm()).thenReturn(new StoredLlmConfig(
                "kimi", null, null, null,
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

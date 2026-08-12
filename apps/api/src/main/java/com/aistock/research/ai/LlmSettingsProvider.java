package com.aistock.research.ai;

import com.aistock.research.configuration.RuntimeConfigStore;
import com.aistock.research.configuration.LlmProviderPolicy;
import com.aistock.research.configuration.StoredLlmConfig;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

@Service
public class LlmSettingsProvider {

    private final RuntimeConfigStore store;
    private final LlmApiKeyEnvironment environment;
    private final LlmProviderPolicy providerPolicy;

    public LlmSettingsProvider(
            RuntimeConfigStore store,
            LlmApiKeyEnvironment environment,
            LlmProviderPolicy providerPolicy
    ) {
        this.store = store;
        this.environment = environment;
        this.providerPolicy = providerPolicy;
    }

    public LlmSettings current() {
        return resolve(store.readLlm());
    }

    public LlmSettings resolve(StoredLlmConfig stored) {
        Objects.requireNonNull(stored, "数据库大模型配置不能为空");
        String provider = providerPolicy.canonicalProvider(stored.provider());
        ResolvedKey key = resolveKey(stored, provider);
        String model = firstNonBlank(stored.model(), providerPolicy.defaultModel(provider));
        String baseUrl = providerPolicy.trustedBaseUrl(provider, firstNonBlank(
                stored.baseUrl(),
                providerPolicy.defaultBaseUrl(provider)
        ));
        String responseFormat = normalizeResponseFormat(firstNonBlank(
                stored.responseFormat(),
                providerPolicy.defaultResponseFormat(provider)
        ));
        Integer maxCompletionTokens = stored.maxCompletionTokens() == null
                ? providerPolicy.defaultMaxCompletionTokens(provider)
                : stored.maxCompletionTokens();
        return new LlmSettings(
                provider,
                key.value(),
                key.source(),
                model.trim(),
                trimTrailingSlash(baseUrl.trim()),
                responseFormat,
                stored.strictJsonSchema(),
                trimmedOrNull(stored.thinking()),
                maxCompletionTokens,
                stored.temperature()
        );
    }

    public LlmConfigPreview preview(StoredLlmConfig stored) {
        LlmSettings settings = resolve(stored);
        return new LlmConfigPreview(
                settings.provider(),
                settings.model(),
                settings.baseUrl(),
                settings.responseFormat(),
                settings.strictJsonSchema(),
                hasText(settings.apiKey()),
                settings.apiKeySource(),
                settings.thinking(),
                settings.maxCompletionTokens(),
                settings.temperature()
        );
    }

    private ResolvedKey resolveKey(StoredLlmConfig stored, String provider) {
        if (hasText(stored.apiKey())) {
            return new ResolvedKey(stored.apiKey().trim(), "database");
        }
        String configuredName = providerPolicy.apiKeyEnv(provider, stored.apiKeyEnv());
        String configuredValue = environmentValue(configuredName);
        if (hasText(configuredValue)) {
            return new ResolvedKey(configuredValue.trim(), "env:" + configuredName);
        }
        String defaultName = providerPolicy.defaultApiKeyEnv(provider);
        String defaultValue = environmentValue(defaultName);
        if (hasText(defaultValue)) {
            return new ResolvedKey(defaultValue.trim(), "env:" + defaultName);
        }
        return new ResolvedKey(null, "missing");
    }

    private String normalizeResponseFormat(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String environmentValue(String name) {
        return hasText(name) ? environment.value(name) : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String trimmedOrNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ResolvedKey(String value, String source) {
    }
}

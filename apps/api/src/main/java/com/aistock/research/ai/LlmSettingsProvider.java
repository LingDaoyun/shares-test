package com.aistock.research.ai;

import com.aistock.research.configuration.RuntimeConfigStore;
import com.aistock.research.configuration.StoredLlmConfig;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

@Service
public class LlmSettingsProvider {

    private static final String OPENAI_PROVIDER = "openai";
    private static final String MOONSHOT_PROVIDER = "moonshot";
    private static final String KIMI_CODE_PROVIDER = "kimi-code";
    private static final String DEEPSEEK_PROVIDER = "deepseek";

    private final RuntimeConfigStore store;
    private final Environment environment;

    public LlmSettingsProvider(RuntimeConfigStore store, Environment environment) {
        this.store = store;
        this.environment = environment;
    }

    public LlmSettings current() {
        return resolve(store.readLlm());
    }

    public LlmSettings resolve(StoredLlmConfig stored) {
        Objects.requireNonNull(stored, "数据库大模型配置不能为空");
        String provider = canonicalProvider(firstNonBlank(stored.provider(), OPENAI_PROVIDER));
        ResolvedKey key = resolveKey(stored, provider);
        String model = firstNonBlank(stored.model(), defaultModel(provider));
        String baseUrl = firstNonBlank(stored.baseUrl(), defaultBaseUrl(provider));
        String responseFormat = normalizeResponseFormat(firstNonBlank(
                stored.responseFormat(),
                defaultResponseFormat(provider)
        ));
        Integer maxCompletionTokens = stored.maxCompletionTokens() == null
                ? defaultMaxCompletionTokens(provider)
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
        String configuredName = trimmedOrNull(stored.apiKeyEnv());
        String configuredValue = environmentValue(configuredName);
        if (hasText(configuredValue)) {
            return new ResolvedKey(configuredValue.trim(), "env:" + configuredName);
        }
        String defaultName = defaultKeyEnv(provider);
        String defaultValue = environmentValue(defaultName);
        if (hasText(defaultValue)) {
            return new ResolvedKey(defaultValue.trim(), "env:" + defaultName);
        }
        return new ResolvedKey(null, "missing");
    }

    private String canonicalProvider(String provider) {
        String normalized = provider.toLowerCase(Locale.ROOT).trim();
        if ("kimi-code".equals(normalized) || "kimi_code".equals(normalized)) {
            return KIMI_CODE_PROVIDER;
        }
        if ("deepseek".equals(normalized)
                || "deep_seek".equals(normalized)
                || "deep-seek".equals(normalized)) {
            return DEEPSEEK_PROVIDER;
        }
        if ("kimi".equals(normalized)) {
            return MOONSHOT_PROVIDER;
        }
        return normalized;
    }

    private String defaultBaseUrl(String provider) {
        return switch (provider) {
            case KIMI_CODE_PROVIDER -> "https://api.kimi.com/coding/v1";
            case MOONSHOT_PROVIDER -> "https://api.moonshot.ai/v1";
            case DEEPSEEK_PROVIDER -> "https://api.deepseek.com";
            default -> "https://api.openai.com/v1";
        };
    }

    private String defaultModel(String provider) {
        return switch (provider) {
            case KIMI_CODE_PROVIDER -> "kimi-for-coding";
            case MOONSHOT_PROVIDER -> "kimi-k2.6";
            case DEEPSEEK_PROVIDER -> "deepseek-v4-pro";
            default -> "gpt-5.5";
        };
    }

    private String defaultKeyEnv(String provider) {
        return switch (provider) {
            case KIMI_CODE_PROVIDER -> "KIMI_API_KEY";
            case MOONSHOT_PROVIDER -> "MOONSHOT_API_KEY";
            case DEEPSEEK_PROVIDER -> "DEEPSEEK_API_KEY";
            default -> "OPENAI_API_KEY";
        };
    }

    private String defaultResponseFormat(String provider) {
        return DEEPSEEK_PROVIDER.equals(provider) ? "json_object" : "json_schema";
    }

    private Integer defaultMaxCompletionTokens(String provider) {
        return DEEPSEEK_PROVIDER.equals(provider) ? 8192 : null;
    }

    private String normalizeResponseFormat(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String environmentValue(String name) {
        return hasText(name) ? environment.getProperty(name) : null;
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

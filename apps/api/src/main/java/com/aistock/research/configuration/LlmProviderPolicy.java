package com.aistock.research.configuration;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

@Component
public class LlmProviderPolicy {

    private static final Map<String, ProviderProfile> PROFILES = Map.of(
            "openai", new ProviderProfile(
                    "api.openai.com",
                    "https://api.openai.com/v1",
                    "OPENAI_API_KEY",
                    "gpt-5.5",
                    "json_schema",
                    null
            ),
            "moonshot", new ProviderProfile(
                    "api.moonshot.ai",
                    "https://api.moonshot.ai/v1",
                    "MOONSHOT_API_KEY",
                    "kimi-k2.6",
                    "json_schema",
                    null
            ),
            "kimi-code", new ProviderProfile(
                    "api.kimi.com",
                    "https://api.kimi.com/coding/v1",
                    "KIMI_API_KEY",
                    "kimi-for-coding",
                    "json_schema",
                    null
            ),
            "deepseek", new ProviderProfile(
                    "api.deepseek.com",
                    "https://api.deepseek.com",
                    "DEEPSEEK_API_KEY",
                    "deepseek-v4-pro",
                    "json_object",
                    8192
            )
    );

    public String canonicalProvider(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider 不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "kimi" -> "moonshot";
            case "kimi_code" -> "kimi-code";
            case "deep_seek", "deep-seek" -> "deepseek";
            default -> normalized;
        };
        if (!PROFILES.containsKey(normalized)) {
            throw new IllegalArgumentException("不支持的 LLM Provider: " + normalized);
        }
        return normalized;
    }

    public String trustedBaseUrl(String provider, String value) {
        String canonical = canonicalProvider(provider);
        if (value == null || value.isBlank()) {
            return profile(canonical).defaultBaseUrl();
        }
        String normalized = value.trim();
        try {
            URI uri = URI.create(normalized);
            ProviderProfile profile = profile(canonical);
            boolean trusted = "https".equalsIgnoreCase(uri.getScheme())
                    && profile.host().equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
            if (!trusted) {
                throw new IllegalArgumentException(trustedBaseUrlMessage(canonical, profile));
            }
            return trimTrailingSlash(normalized);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("Base URL")) {
                throw exception;
            }
            throw new IllegalArgumentException(trustedBaseUrlMessage(canonical, profile(canonical)));
        }
    }

    public String apiKeyEnv(String provider, String value) {
        String canonical = canonicalProvider(provider);
        String expected = profile(canonical).apiKeyEnv();
        if (value == null || value.isBlank()) {
            return expected;
        }
        String normalized = value.trim();
        if (!expected.equals(normalized)) {
            throw new IllegalArgumentException(
                    canonical + " 只允许使用 API Key 环境变量 " + expected
            );
        }
        return normalized;
    }

    public String defaultBaseUrl(String provider) {
        return profile(canonicalProvider(provider)).defaultBaseUrl();
    }

    public String defaultApiKeyEnv(String provider) {
        return profile(canonicalProvider(provider)).apiKeyEnv();
    }

    public String defaultModel(String provider) {
        return profile(canonicalProvider(provider)).defaultModel();
    }

    public String defaultResponseFormat(String provider) {
        return profile(canonicalProvider(provider)).defaultResponseFormat();
    }

    public Integer defaultMaxCompletionTokens(String provider) {
        return profile(canonicalProvider(provider)).defaultMaxCompletionTokens();
    }

    public boolean sameCredentialScope(StoredLlmConfig before, StoredLlmConfig after) {
        return canonicalProvider(before.provider()).equals(canonicalProvider(after.provider()));
    }

    private ProviderProfile profile(String canonicalProvider) {
        return PROFILES.get(canonicalProvider);
    }

    private String trustedBaseUrlMessage(String provider, ProviderProfile profile) {
        return "Base URL 必须使用 " + provider + " 官方 HTTPS 地址 " + profile.defaultBaseUrl();
    }

    private String trimTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/") && normalized.length() > "https://".length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record ProviderProfile(
            String host,
            String defaultBaseUrl,
            String apiKeyEnv,
            String defaultModel,
            String defaultResponseFormat,
            Integer defaultMaxCompletionTokens
    ) {
    }
}

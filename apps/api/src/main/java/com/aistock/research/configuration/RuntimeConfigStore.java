package com.aistock.research.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.aistock.research.configuration.RuntimeConfigSectionKey.LLM;
import static com.aistock.research.configuration.RuntimeConfigSectionKey.POLICY_SOURCES;

@Service
public class RuntimeConfigStore {

    private final RuntimeConfigSectionRepository repository;
    private final ObjectMapper objectMapper;
    private final RuntimeConfigDefaults defaults;

    public RuntimeConfigStore(
            RuntimeConfigSectionRepository repository,
            ObjectMapper objectMapper,
            RuntimeConfigDefaults defaults
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.defaults = defaults;
    }

    @Transactional
    public void initializeMissingSections() {
        insertIfMissing(LLM, write(LLM, defaults.llm()));
        insertIfMissing(POLICY_SOURCES, write(
                POLICY_SOURCES,
                new StoredPolicySources(defaults.policySources())
        ));
    }

    @Transactional(readOnly = true)
    public StoredLlmConfig readLlm() {
        return parseLlm(required(LLM).getPayloadJson());
    }

    @Transactional(readOnly = true)
    public List<PolicySourceConfig> readPolicySources() {
        return parsePolicySources(required(POLICY_SOURCES).getPayloadJson()).sources();
    }

    @Transactional(readOnly = true)
    public RuntimeConfigState readState() {
        RuntimeConfigSectionEntity llmRow = required(LLM);
        RuntimeConfigSectionEntity policyRow = required(POLICY_SOURCES);
        return state(llmRow, policyRow);
    }

    @Transactional
    public StoredLlmConfig updateLlm(LlmRuntimeConfig request) {
        RuntimeConfigSectionEntity row = locked(LLM);
        return updateLlmRow(row, request);
    }

    @Transactional
    public List<PolicySourceConfig> updatePolicySources(List<PolicySourceConfig> request) {
        RuntimeConfigSectionEntity row = locked(POLICY_SOURCES);
        return updatePolicySourcesRow(row, request);
    }

    @Transactional
    public RuntimeConfigState updateAll(
            LlmRuntimeConfig llm,
            List<PolicySourceConfig> policySources
    ) {
        RuntimeConfigSectionEntity llmRow = locked(LLM);
        RuntimeConfigSectionEntity policyRow = locked(POLICY_SOURCES);
        if (llm != null) {
            updateLlmRow(llmRow, llm);
        }
        if (policySources != null) {
            updatePolicySourcesRow(policyRow, policySources);
        }
        return state(llmRow, policyRow);
    }

    private void insertIfMissing(RuntimeConfigSectionKey key, String payload) {
        if (repository.existsById(key.name())) {
            return;
        }
        repository.save(new RuntimeConfigSectionEntity(
                key.name(),
                payload,
                0,
                Instant.now()
        ));
    }

    private StoredLlmConfig updateLlmRow(
            RuntimeConfigSectionEntity row,
            LlmRuntimeConfig request
    ) {
        Objects.requireNonNull(request, "大模型配置不能为空");
        StoredLlmConfig before = parseLlm(row.getPayloadJson());
        String preservedKey = hasText(request.apiKey())
                ? request.apiKey().trim()
                : before.apiKey();
        StoredLlmConfig next = normalizeLlm(request, preservedKey);
        row.replacePayload(write(LLM, next), row.getRevision() + 1, Instant.now());
        return next;
    }

    private List<PolicySourceConfig> updatePolicySourcesRow(
            RuntimeConfigSectionEntity row,
            List<PolicySourceConfig> request
    ) {
        Objects.requireNonNull(request, "政策源配置不能为空");
        List<PolicySourceConfig> normalized = request.stream()
                .map(this::normalizePolicySource)
                .toList();
        row.replacePayload(
                write(POLICY_SOURCES, new StoredPolicySources(normalized)),
                row.getRevision() + 1,
                Instant.now()
        );
        return normalized;
    }

    private RuntimeConfigState state(
            RuntimeConfigSectionEntity llmRow,
            RuntimeConfigSectionEntity policyRow
    ) {
        Instant updatedAt = llmRow.getUpdatedAt().isAfter(policyRow.getUpdatedAt())
                ? llmRow.getUpdatedAt()
                : policyRow.getUpdatedAt();
        return new RuntimeConfigState(
                parseLlm(llmRow.getPayloadJson()),
                llmRow.getRevision(),
                parsePolicySources(policyRow.getPayloadJson()).sources(),
                policyRow.getRevision(),
                updatedAt
        );
    }

    private StoredLlmConfig normalizeLlm(LlmRuntimeConfig request, String apiKey) {
        String provider = requiredText(request.provider(), "Provider 不能为空").toLowerCase(Locale.ROOT);
        String model = requiredText(request.model(), "模型不能为空");
        String baseUrl = requiredHttpUrl(request.baseUrl(), "Base URL 必须是有效的 http 或 https 地址");
        String responseFormat = requiredText(request.responseFormat(), "Response Format 不能为空");
        if (request.maxCompletionTokens() != null && request.maxCompletionTokens() <= 0) {
            throw new IllegalArgumentException("最大 Token 必须大于 0");
        }
        if (request.temperature() != null
                && (request.temperature() < 0.0 || request.temperature() > 2.0)) {
            throw new IllegalArgumentException("Temperature 必须在 0 到 2 之间");
        }
        return new StoredLlmConfig(
                provider,
                trimmedOrNull(apiKey),
                trimmedOrNull(request.apiKeyEnv()),
                model,
                baseUrl,
                responseFormat,
                request.strictJsonSchema(),
                trimmedOrNull(request.thinking()),
                request.maxCompletionTokens(),
                request.temperature()
        );
    }

    private PolicySourceConfig normalizePolicySource(PolicySourceConfig source) {
        Objects.requireNonNull(source, "政策源不能为空");
        if (source.weight() < 1 || source.weight() > 100) {
            throw new IllegalArgumentException("政策源权重必须在 1 到 100 之间");
        }
        return new PolicySourceConfig(
                requiredText(source.name(), "政策源名称不能为空"),
                requiredText(source.type(), "政策源类型不能为空").toLowerCase(Locale.ROOT),
                requiredHttpUrl(source.url(), "政策源 URL 必须是有效的 http 或 https 地址"),
                source.weight()
        );
    }

    private RuntimeConfigSectionEntity required(RuntimeConfigSectionKey key) {
        return repository.findById(key.name())
                .orElseThrow(() -> new IllegalStateException("运行配置栏目不存在: " + key));
    }

    private RuntimeConfigSectionEntity locked(RuntimeConfigSectionKey key) {
        return repository.findForUpdate(key.name())
                .orElseThrow(() -> new IllegalStateException("运行配置栏目不存在: " + key));
    }

    private StoredLlmConfig parseLlm(String payload) {
        return read(LLM, payload, StoredLlmConfig.class);
    }

    private StoredPolicySources parsePolicySources(String payload) {
        return read(POLICY_SOURCES, payload, StoredPolicySources.class);
    }

    private <T> T read(RuntimeConfigSectionKey key, String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库运行配置损坏: " + key, exception);
        }
    }

    private String write(RuntimeConfigSectionKey key, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("运行配置序列化失败: " + key, exception);
        }
    }

    private String requiredText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String requiredHttpUrl(String value, String message) {
        String normalized = requiredText(value, message);
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || (!("http".equalsIgnoreCase(scheme)) && !("https".equalsIgnoreCase(scheme)))) {
                throw new IllegalArgumentException(message);
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimmedOrNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}

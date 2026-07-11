package com.aistock.research.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TrendAnalysisArchiveService {

    private final TrendAnalysisRunRepository repository;
    private final TrendRequestEnrichmentService enrichmentService;
    private final TrendPromptService trendPromptService;
    private final LlmTrendAnalysisService llmTrendAnalysisService;
    private final ObjectMapper objectMapper;

    public TrendAnalysisArchiveService(
            TrendAnalysisRunRepository repository,
            TrendRequestEnrichmentService enrichmentService,
            TrendPromptService trendPromptService,
            LlmTrendAnalysisService llmTrendAnalysisService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.enrichmentService = enrichmentService;
        this.trendPromptService = trendPromptService;
        this.llmTrendAnalysisService = llmTrendAnalysisService;
        this.objectMapper = objectMapper;
    }

    public TrendAnalysisResponse analyze(TrendPromptRequest request) {
        TrendPromptRequest enriched = enrichmentService.enrich(request);
        TrendPromptPreview promptPreview = trendPromptService.preview(enriched);
        LocalDate today = LocalDate.now();
        String fingerprint = fingerprint(enriched, promptPreview);

        Optional<TrendAnalysisRunEntity> existing = repository.findByAnalysisDateAndRequestFingerprint(today, fingerprint);
        if (existing.isPresent()) {
            return toResponse(existing.get(), true);
        }

        TrendAnalysisResponse generated = llmTrendAnalysisService.analyze(enriched);
        TrendAnalysisRunEntity entity = toEntity(today, fingerprint, enriched, promptPreview, generated);
        try {
            TrendAnalysisRunEntity saved = repository.save(entity);
            return toResponse(saved, false);
        } catch (DataIntegrityViolationException exception) {
            return repository.findByAnalysisDateAndRequestFingerprint(today, fingerprint)
                    .map(record -> toResponse(record, true))
                    .orElseThrow(() -> exception);
        }
    }

    public Optional<TrendAnalysisResponse> findLatestForToday(TrendPromptRequest request) {
        TrendPromptRequest enriched = enrichmentService.enrich(request);
        TrendPromptPreview promptPreview = trendPromptService.preview(enriched);
        return repository.findByAnalysisDateAndRequestFingerprint(LocalDate.now(), fingerprint(enriched, promptPreview))
                .map(record -> toResponse(record, true));
    }

    public List<TrendAnalysisHistoryItem> listHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findAllByOrderByAnalyzedAtDesc(PageRequest.of(0, safeLimit)).stream()
                .map(this::toHistoryItem)
                .toList();
    }

    private TrendAnalysisRunEntity toEntity(
            LocalDate analysisDate,
            String fingerprint,
            TrendPromptRequest request,
            TrendPromptPreview promptPreview,
            TrendAnalysisResponse response
    ) {
        TrendAnalysisRunEntity entity = new TrendAnalysisRunEntity();
        entity.setAnalysisDate(analysisDate);
        entity.setRequestFingerprint(fingerprint);
        entity.setDocumentTitle(request.documentTitle());
        entity.setDocumentType(request.documentType());
        entity.setSourceOrganization(request.sourceOrganization());
        entity.setPublishedAt(request.publishedAt());
        entity.setSourceUrl(request.sourceUrl());
        entity.setPromptName(response.promptName());
        entity.setPromptVersion(response.promptVersion());
        entity.setProvider(response.provider());
        entity.setModel(response.model());
        entity.setResponseId(response.responseId());
        entity.setRequestPayload(writeValue(request));
        entity.setPromptPreviewPayload(writeValue(promptPreview));
        entity.setAnalysisPayload(writeValue(response.analysis()));
        entity.setUsagePayload(writeValue(response.usage()));
        entity.setAnalyzedAt(response.analyzedAt());
        return entity;
    }

    private TrendAnalysisResponse toResponse(TrendAnalysisRunEntity entity, boolean cached) {
        return new TrendAnalysisResponse(
                entity.getId(),
                cached,
                entity.getProvider(),
                entity.getModel(),
                entity.getPromptName(),
                entity.getPromptVersion(),
                entity.getResponseId(),
                readJsonNode(entity.getAnalysisPayload()),
                readUsage(entity.getUsagePayload()),
                entity.getAnalyzedAt()
        );
    }

    private TrendAnalysisHistoryItem toHistoryItem(TrendAnalysisRunEntity entity) {
        JsonNode analysis = readJsonNode(entity.getAnalysisPayload());
        JsonNode overall = analysis.path("overall_assessment");
        return new TrendAnalysisHistoryItem(
                entity.getId(),
                entity.getAnalysisDate(),
                entity.getDocumentTitle(),
                entity.getSourceOrganization(),
                entity.getPublishedAt(),
                entity.getSourceUrl(),
                entity.getPromptVersion(),
                entity.getProvider(),
                entity.getModel(),
                overall.path("summary").asText(null),
                overall.path("confidence").asText(null),
                overall.path("next_action").asText(null),
                entity.getAnalyzedAt()
        );
    }

    private JsonNode readJsonNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("读取已归档分析结果失败", exception);
        }
    }

    private Map<String, Object> readUsage(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("读取已归档 usage 失败", exception);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("归档趋势分析结果失败", exception);
        }
    }

    private String fingerprint(TrendPromptRequest request, TrendPromptPreview promptPreview) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("documentTitle", normalize(request.documentTitle()));
        normalized.put("documentType", normalize(request.documentType()));
        normalized.put("sourceOrganization", normalize(request.sourceOrganization()));
        normalized.put("publishedAt", normalize(request.publishedAt()));
        normalized.put("sourceUrl", normalize(request.sourceUrl()));
        normalized.put("contentExcerpt", normalize(request.contentExcerpt()));
        normalized.put("focusThemes", request.focusThemes() == null ? List.of() : request.focusThemes().stream().map(this::normalize).sorted().toList());
        normalized.put("knownCompanies", request.knownCompanies() == null ? List.of() : request.knownCompanies().stream().map(this::normalize).sorted().toList());
        normalized.put("promptName", promptPreview.name());
        normalized.put("promptVersion", promptPreview.version());
        String payload = writeValue(normalized);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("生成趋势分析指纹失败", exception);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace("\r\n", "\n");
    }
}

package com.aistock.research.policy;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 政策主题的真 Agent 复核：把规则引擎产出的主题、信号与公司池交给大模型，
 * 由三个视角（政策原文 / 行业关联 / 时效）重新核对并输出结构化结论。
 * 与 PolicyThemeService 内基于关键词的确定性“Agent 标签”相互独立、可交叉验证。
 */
@Service
public class PolicyAiReviewService {

    private static final long THEME_INDEX_CACHE_SECONDS = 300;

    private final LlmChatClient llmChatClient;
    private final PolicyThemeService themeService;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedThemeIndex> themeIndexCache = new ConcurrentHashMap<>();

    private record CachedThemeIndex(Map<String, PolicyTheme> index, Instant expiresAt) {
    }

    public PolicyAiReviewService(
            LlmChatClient llmChatClient,
            PolicyThemeService themeService,
            ObjectMapper objectMapper
    ) {
        this.llmChatClient = llmChatClient;
        this.themeService = themeService;
        this.objectMapper = objectMapper;
    }

    public PolicyAiReview review(String themeCode) {
        PolicyTheme theme = cachedThemeIndex().get(themeCode);
        if (theme == null) {
            throw new NoSuchElementException("未知政策主题：" + themeCode);
        }
        LlmConfigPreview config = llmChatClient.currentConfig();
        if (!config.apiKeyConfigured()) {
            return PolicyAiReview.unconfigured(theme.themeCode(), theme.name());
        }
        try {
            JsonNode json = llmChatClient.completeJson(
                    modelInstruction(),
                    userPrompt(theme),
                    "policy_theme_review",
                    outputSchema()
            );
            return toReview(theme, config, json);
        } catch (RuntimeException exception) {
            return PolicyAiReview.unavailable(
                    theme.themeCode(),
                    theme.name(),
                    "AI 复核调用失败：" + rootMessage(exception)
            );
        }
    }

    // listThemes 每次都会重新抓取政策源与公司池（约十几秒），
    // 复核按钮会被反复点击，这里按 5 分钟缓存主题索引。
    private Map<String, PolicyTheme> cachedThemeIndex() {
        Instant now = Instant.now();
        CachedThemeIndex cached = themeIndexCache.get("default");
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.index();
        }
        Map<String, PolicyTheme> index = themeService.themeIndex();
        if (index.isEmpty()) {
            return index;
        }
        themeIndexCache.put("default", new CachedThemeIndex(index, now.plusSeconds(THEME_INDEX_CACHE_SECONDS)));
        return index;
    }

    String modelInstruction() {
        return "你是 A 股政策研究中的复核 Agent 委员会，由三个独立视角组成："
                + "政策原文Agent（核对每条信号与政策原文、来源层级是否一致，识别标题党和过度解读）、"
                + "行业关联Agent（评估政策与主题产业链的真实关联度、传导路径和受益环节）、"
                + "时效Agent（判断政策处于发酵初期、持续发酵还是边际退潮）。"
                + "你只依据输入数据复核，不得编造输入之外的事实；"
                + "对规则引擎结论给出确认或异议，无法核实的判断写入 must_verify。"
                + "严格按 JSON Schema 输出，不输出任何额外文本。";
    }

    String userPrompt(PolicyTheme theme) {
        return "请复核以下政策主题：\n" + safeSerialize(themePayload(theme))
                + "\n\n复核要求：\n"
                + "1. relevance_score：政策与该主题产业链的真实关联度（0-100），只有原文明确指向该产业才给高分。\n"
                + "2. direction：综合判断 POSITIVE / NEUTRAL / NEGATIVE。\n"
                + "3. phase：政策发酵阶段 EARLY / ONGOING / FADING。\n"
                + "4. logic_summary：一句话概括核心传导逻辑。\n"
                + "5. confirmations：规则引擎结论中被你确认的点。\n"
                + "6. disagreements：你认为需要降级或质疑的点，没有则返回空数组。\n"
                + "7. risks：主要风险或证伪条件。\n"
                + "8. must_verify：落笔前必须人工核实的事项。\n"
                + "9. confidence：你本次复核的置信度（0-100）。";
    }

    Map<String, Object> outputSchema() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.put("required", List.of(
                "relevance_score", "direction", "phase", "logic_summary",
                "confirmations", "disagreements", "risks", "must_verify", "confidence"
        ));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("relevance_score", Map.of("type", "integer"));
        properties.put("direction", Map.of("type", "string", "enum", List.of("POSITIVE", "NEUTRAL", "NEGATIVE")));
        properties.put("phase", Map.of("type", "string", "enum", List.of("EARLY", "ONGOING", "FADING")));
        properties.put("logic_summary", Map.of("type", "string"));
        properties.put("confirmations", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("disagreements", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("risks", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("must_verify", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("confidence", Map.of("type", "integer"));
        root.put("properties", properties);
        return root;
    }

    private PolicyAiReview toReview(PolicyTheme theme, LlmConfigPreview config, JsonNode json) {
        List<String> dataGaps = new ArrayList<>();
        String direction = normalizeEnum(json.path("direction").asText(null), List.of("POSITIVE", "NEUTRAL", "NEGATIVE"));
        String phase = normalizeEnum(json.path("phase").asText(null), List.of("EARLY", "ONGOING", "FADING"));
        if (direction == null) {
            dataGaps.add("AI 未给出合法的政策方向，按待定处理。");
        }
        if (phase == null) {
            dataGaps.add("AI 未给出合法的发酵阶段，按待定处理。");
        }
        String logicSummary = textOrNull(json.path("logic_summary"));
        if (logicSummary == null) {
            dataGaps.add("AI 未返回核心传导逻辑。");
        }
        return new PolicyAiReview(
                theme.themeCode(),
                theme.name(),
                config.provider(),
                config.model(),
                clampScore(json.path("relevance_score")),
                direction,
                PolicyAiReview.directionLabel(direction),
                phase,
                PolicyAiReview.phaseLabel(phase),
                logicSummary,
                stringList(json.path("confirmations")),
                stringList(json.path("disagreements")),
                stringList(json.path("risks")),
                stringList(json.path("must_verify")),
                clampScore(json.path("confidence")),
                Instant.now(),
                dataGaps
        );
    }

    private Map<String, Object> themePayload(PolicyTheme theme) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("theme_code", theme.themeCode());
        payload.put("name", theme.name());
        payload.put("policy_level", theme.policyLevel());
        payload.put("time_horizon", theme.timeHorizon());
        payload.put("strength_score", theme.strengthScore());
        payload.put("chain_segments", theme.chainSegments());
        payload.put("rule_engine_risks", theme.risks());
        payload.put("signals", theme.signals().stream()
                .map(signal -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source", signal.source());
                    item.put("signal_type", signal.signalType());
                    item.put("summary", signal.summary());
                    item.put("confidence", signal.confidence());
                    item.put("published_at", signal.publishedAt());
                    item.put("url", signal.url());
                    return item;
                })
                .toList());
        payload.put("company_pool", theme.companyPool().stream()
                .limit(5)
                .map(candidate -> Map.of(
                        "symbol", candidate.symbol(),
                        "name", candidate.companyName(),
                        "chain_segment", candidate.chainSegment()
                ))
                .toList());
        return payload;
    }

    private String safeSerialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private String normalizeEnum(String value, List<String> allowed) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return allowed.contains(normalized) ? normalized : null;
    }

    private Integer clampScore(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        int value = node.asInt();
        return Math.max(0, Math.min(100, value));
    }

    private String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return List.copyOf(values);
    }

    private String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}

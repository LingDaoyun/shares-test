package com.aistock.research.leader;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LeaderAiReviewService {

    static final String PROMPT_VERSION = "potential-leader-ai-v1";
    private static final Set<String> VERDICTS = Set.of("SUPPORT", "NEUTRAL", "OPPOSE");
    private static final Set<String> PHASES = Set.of("EMERGING", "COMPETING", "CROWDED", "FADING");

    private final PotentialLeaderService potentialLeaderService;
    private final LlmChatClient llmChatClient;
    private final LeaderSnapshotStore snapshotStore;
    private final ObjectMapper objectMapper;

    public LeaderAiReviewService(
            PotentialLeaderService potentialLeaderService,
            LlmChatClient llmChatClient,
            LeaderSnapshotStore snapshotStore,
            ObjectMapper objectMapper
    ) {
        this.potentialLeaderService = potentialLeaderService;
        this.llmChatClient = llmChatClient;
        this.snapshotStore = snapshotStore;
        this.objectMapper = objectMapper;
    }

    public LeaderAiReview review(
            PotentialLeaderSnapshot.Lane lane,
            String symbol,
            String expectedHash
    ) {
        PotentialLeaderSnapshot.Candidate candidate = potentialLeaderService.currentCandidate(
                lane, symbol, expectedHash);
        if (!constantTimeEquals(candidate.snapshotHash(), expectedHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "候选快照已变化，请刷新后重试");
        }
        LocalDate tradeDate = potentialLeaderService.currentTradeDate();
        LlmConfigPreview config = null;
        LeaderAiReview review;
        try {
            config = llmChatClient.currentConfig();
            JsonNode response = llmChatClient.completeJson(
                    systemPrompt(), userPrompt(candidate), "potential_leader_ai_review", outputSchema());
            review = success(candidate, tradeDate, config, response);
        } catch (RuntimeException exception) {
            review = failure(candidate, tradeDate, config, rootMessage(exception));
        }
        snapshotStore.saveAiReview(review);
        return review;
    }

    String systemPrompt() {
        return "你是 A 股潜力龙头的影子复核员。你只能依据输入中的确定性评分分项、入选证据、"
                + "风险扣分、龙虎榜营业部席位和数据缺口进行反证式复核；不得补充外部传闻，不得映射"
                + "所谓知名游资身份，不得修改正式分、等级、排名或候选资格，也不得给出买卖指令。"
                + "严格输出符合 JSON Schema 的单个对象，不输出额外文本。";
    }

    String userPrompt(PotentialLeaderSnapshot.Candidate candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lane", candidate.lane());
        payload.put("symbol", candidate.symbol());
        payload.put("name", candidate.name());
        payload.put("industry", candidate.industry());
        payload.put("official_rank", candidate.rank());
        payload.put("official_grade", candidate.grade());
        payload.put("official_score", candidate.score());
        payload.put("snapshot_hash", candidate.snapshotHash());
        payload.put("score_breakdown", candidate.scoreBreakdown());
        payload.put("deterministic_evidence", candidate.evidence());
        payload.put("risk_deductions", candidate.riskDeductions());
        payload.put("dragon_tiger_status", candidate.dragonTiger() == null
                ? null : candidate.dragonTiger().status());
        payload.put("dragon_tiger_seats", candidate.dragonTiger() == null
                ? List.of() : candidate.dragonTiger().seats());
        payload.put("data_gaps", candidate.dragonTiger() == null
                ? List.of() : candidate.dragonTiger().dataGaps());
        return "请在不改变正式结果的前提下复核下列服务端事实。"
                + "verdict 仅可为 SUPPORT/NEUTRAL/OPPOSE；phase 仅可为 "
                + "EMERGING/COMPETING/CROWDED/FADING；confidence 为 0 到 1。\n"
                + serialize(payload);
    }

    Map<String, Object> outputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("verdict", Map.of("type", "string", "enum", List.copyOf(VERDICTS)));
        properties.put("phase", Map.of("type", "string", "enum", List.copyOf(PHASES)));
        properties.put("advantages", stringArraySchema());
        properties.put("counter_evidence", stringArraySchema());
        properties.put("data_gaps", stringArraySchema());
        properties.put("must_verify", stringArraySchema());
        properties.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of(
                "verdict", "phase", "advantages", "counter_evidence",
                "data_gaps", "must_verify", "confidence"));
        schema.put("properties", properties);
        return schema;
    }

    private LeaderAiReview success(
            PotentialLeaderSnapshot.Candidate candidate,
            LocalDate tradeDate,
            LlmConfigPreview config,
            JsonNode response
    ) {
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("AI 返回结构不是对象");
        }
        String verdict = requiredEnum(response, "verdict", VERDICTS);
        String phase = requiredEnum(response, "phase", PHASES);
        List<String> advantages = requiredStrings(response, "advantages", 3);
        List<String> counterEvidence = requiredStrings(response, "counter_evidence", 3);
        List<String> dataGaps = requiredStrings(response, "data_gaps", 6);
        List<String> mustVerify = requiredStrings(response, "must_verify", 6);
        JsonNode confidenceNode = response.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) {
            throw new IllegalStateException("AI confidence 缺失或不是数值");
        }
        BigDecimal confidence = confidenceNode.decimalValue();
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("AI confidence 必须位于 0 到 1");
        }
        return new LeaderAiReview(
                UUID.randomUUID(), tradeDate, candidate.lane(), candidate.symbol(),
                LeaderRuleSet.RULE_VERSION, candidate.snapshotHash(), "SUCCEEDED", verdict, phase,
                advantages, counterEvidence, dataGaps, mustVerify, confidence,
                config == null ? null : config.provider(), config == null ? null : config.model(),
                PROMPT_VERSION, null, Instant.now());
    }

    private LeaderAiReview failure(
            PotentialLeaderSnapshot.Candidate candidate,
            LocalDate tradeDate,
            LlmConfigPreview config,
            String error
    ) {
        String message = "AI 影子复核失败：" + error;
        return new LeaderAiReview(
                UUID.randomUUID(), tradeDate, candidate.lane(), candidate.symbol(),
                LeaderRuleSet.RULE_VERSION, candidate.snapshotHash(), "FAILED", null, null,
                List.of(), List.of(), List.of(message), List.of(), null,
                config == null ? null : config.provider(), config == null ? null : config.model(),
                PROMPT_VERSION, bounded(message), Instant.now());
    }

    private String requiredEnum(JsonNode root, String field, Set<String> allowed) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || !allowed.contains(value.asText())) {
            throw new IllegalStateException("AI " + field + " 非法");
        }
        return value.asText();
    }

    private List<String> requiredStrings(JsonNode root, String field, int maximum) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            throw new IllegalStateException("AI " + field + " 缺失或不是数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                throw new IllegalStateException("AI " + field + " 包含非字符串");
            }
            String value = item.asText().trim();
            if (!value.isBlank()) {
                values.add(value);
                if (values.size() == maximum) {
                    break;
                }
            }
        }
        return List.copyOf(values);
    }

    private Map<String, Object> stringArraySchema() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("AI 复核事实序列化失败", exception);
        }
    }

    private boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private String bounded(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}

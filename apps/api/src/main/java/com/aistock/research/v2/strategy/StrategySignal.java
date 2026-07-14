package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record StrategySignal(
        StrategyCode strategyCode,
        String strategyVersion,
        String symbol,
        String companyName,
        Instant decisionAt,
        Instant dataCutoffAt,
        CandidateStage candidateStage,
        StrategyAction action,
        BigDecimal positionLimit,
        String entryCondition,
        String invalidCondition,
        BigDecimal rankScore,
        BigDecimal dataConfidence,
        BigDecimal historicalHitRate,
        BigDecimal riskReward,
        List<String> evidenceSummary,
        List<String> blockedReasons,
        Map<String, String> context,
        SourceQualityStatus sourceQuality,
        Map<String, Object> replayPayload,
        SignalProvenance signalProvenance
) {
    public StrategySignal {
        requireNonNull(strategyCode, "strategyCode");
        requireNonBlank(strategyVersion, "strategyVersion");
        requireNonBlank(symbol, "symbol");
        requireNonBlank(companyName, "companyName");
        requireNonNull(decisionAt, "decisionAt");
        requireNonNull(dataCutoffAt, "dataCutoffAt");
        requireNonNull(candidateStage, "candidateStage");
        requireNonNull(action, "action");
        requireNonNull(sourceQuality, "sourceQuality");
        requireNonNull(signalProvenance, "signalProvenance");
        evidenceSummary = evidenceSummary == null ? List.of() : List.copyOf(evidenceSummary);
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
        context = context == null ? Map.of() : Map.copyOf(context);
        replayPayload = freezeReplayPayload(normalizeReplayPayload(replayPayload, strategyCode, strategyVersion,
                symbol, companyName, decisionAt, dataCutoffAt, candidateStage, action, context, sourceQuality,
                signalProvenance));
        validateStageAndAction(candidateStage, action, blockedReasons);
        validateSourceQuality(candidateStage, action, sourceQuality, dataConfidence);
        validateProvenance(action, signalProvenance);
    }

    public StrategySignal(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            BigDecimal positionLimit,
            String entryCondition,
            String invalidCondition,
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal historicalHitRate,
            BigDecimal riskReward,
            List<String> evidenceSummary,
            List<String> blockedReasons,
            Map<String, String> context
    ) {
        this(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                candidateStage, action, positionLimit, entryCondition, invalidCondition,
                rankScore, dataConfidence, historicalHitRate, riskReward, evidenceSummary,
                blockedReasons, context, SourceQualityStatus.VERIFIED, Map.of(),
                SignalProvenance.COMPATIBILITY_PROBE);
    }

    public StrategySignal(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            BigDecimal positionLimit,
            String entryCondition,
            String invalidCondition,
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal historicalHitRate,
            BigDecimal riskReward,
            List<String> evidenceSummary,
            List<String> blockedReasons,
            Map<String, String> context,
            SourceQualityStatus sourceQuality
    ) {
        this(strategyCode, strategyVersion, symbol, companyName, decisionAt, dataCutoffAt,
                candidateStage, action, positionLimit, entryCondition, invalidCondition,
                rankScore, dataConfidence, historicalHitRate, riskReward, evidenceSummary,
                blockedReasons, context, sourceQuality, Map.of(),
                SignalProvenance.COMPATIBILITY_PROBE);
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freezeReplayPayload(Map<?, ?> replayPayload) {
        return (Map<String, Object>) freezeJsonValue(replayPayload, new IdentityHashMap<>());
    }

    private static Map<?, ?> normalizeReplayPayload(
            Map<String, Object> replayPayload,
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            Map<String, String> context,
            SourceQualityStatus sourceQuality,
            SignalProvenance signalProvenance
    ) {
        if (replayPayload != null && !replayPayload.isEmpty()) {
            return replayPayload;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("strategyCode", strategyCode.name());
        fallback.put("strategyVersion", strategyVersion);
        fallback.put("symbol", symbol);
        fallback.put("companyName", companyName);
        fallback.put("decisionAt", decisionAt.toString());
        fallback.put("dataCutoffAt", dataCutoffAt.toString());
        fallback.put("candidateStage", candidateStage.name());
        fallback.put("action", action.name());
        fallback.put("sourceQuality", sourceQuality.name());
        fallback.put("signalProvenance", signalProvenance.name());
        fallback.put("context", new LinkedHashMap<>(context));
        return fallback;
    }

    private static Object freezeJsonValue(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)) {
            throw invalidReplayPayload("numeric values must be finite");
        }
        if (value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw invalidReplayPayload("numeric values must be finite");
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            enterReplayContainer(value, ancestors);
            try {
                Map<String, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw invalidReplayPayload("map keys must be strings");
                    }
                    frozen.put(key, freezeJsonValue(entry.getValue(), ancestors));
                }
                return Collections.unmodifiableMap(frozen);
            } finally {
                ancestors.remove(value);
            }
        }
        if (value instanceof List<?> list) {
            enterReplayContainer(value, ancestors);
            try {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object item : list) {
                    frozen.add(freezeJsonValue(item, ancestors));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                ancestors.remove(value);
            }
        }
        throw invalidReplayPayload("unsupported value type: " + value.getClass().getName());
    }

    private static void enterReplayContainer(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (ancestors.put(value, Boolean.TRUE) != null) {
            throw invalidReplayPayload("cyclic map/list reference");
        }
    }

    private static IllegalArgumentException invalidReplayPayload(String detail) {
        return new IllegalArgumentException("replayPayload " + detail);
    }

    private static void validateStageAndAction(
            CandidateStage candidateStage,
            StrategyAction action,
            List<String> blockedReasons
    ) {
        boolean blockedAction = action == StrategyAction.DATA_BLOCKED || action == StrategyAction.RISK_BLOCKED;
        if (candidateStage == CandidateStage.BLOCKED && (!blockedAction || blockedReasons.isEmpty())) {
            throw new IllegalArgumentException("BLOCKED stage requires a blocked action and blockedReasons");
        }
        if (blockedAction && candidateStage != CandidateStage.BLOCKED) {
            throw new IllegalArgumentException(action + " action requires BLOCKED stage");
        }
        if (blockedReasons.stream().anyMatch(reason -> reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("blockedReasons must not contain blank values");
        }
    }

    private static void validateSourceQuality(
            CandidateStage candidateStage,
            StrategyAction action,
            SourceQualityStatus sourceQuality,
            BigDecimal dataConfidence
    ) {
        if (candidateStage != CandidateStage.BLOCKED && dataConfidence == null) {
            throw new IllegalArgumentException("dataConfidence must not be null for research signals");
        }
        if (sourceQuality == SourceQualityStatus.MISSING
                && (candidateStage != CandidateStage.BLOCKED || action != StrategyAction.DATA_BLOCKED)) {
            throw new IllegalArgumentException("MISSING source quality requires DATA_BLOCKED");
        }
    }

    private static void validateProvenance(StrategyAction action, SignalProvenance signalProvenance) {
        if (signalProvenance == SignalProvenance.AI_EVIDENCE_ONLY
                && (action == StrategyAction.ADD
                || action == StrategyAction.LIGHT_TRIAL
                || action == StrategyAction.REDUCE
                || action == StrategyAction.EXIT)) {
            throw new IllegalArgumentException("AI_EVIDENCE_ONLY cannot produce " + action);
        }
    }
}

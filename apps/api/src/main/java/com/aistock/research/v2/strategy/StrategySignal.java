package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
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
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_RISK_REWARD = new BigDecimal("999999.99");

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
        positionLimit = normalizeBoundedDecimal(positionLimit, "positionLimit", BigDecimal.ZERO, BigDecimal.ONE, 4);
        rankScore = normalizeBoundedDecimal(rankScore, "rankScore", BigDecimal.ZERO, HUNDRED, 2);
        dataConfidence = normalizeBoundedDecimal(dataConfidence, "dataConfidence", BigDecimal.ZERO, HUNDRED, 2);
        historicalHitRate = normalizeBoundedDecimal(historicalHitRate, "historicalHitRate", BigDecimal.ZERO, HUNDRED, 2);
        riskReward = normalizeBoundedDecimal(riskReward, "riskReward", BigDecimal.ZERO, MAX_RISK_REWARD, 2);
        evidenceSummary = evidenceSummary == null ? List.of() : List.copyOf(evidenceSummary);
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
        context = context == null ? Map.of() : Map.copyOf(context);
        replayPayload = freezeReplayPayload(normalizeReplayPayload(replayPayload, strategyCode, strategyVersion,
                symbol, companyName, decisionAt, dataCutoffAt, candidateStage, action, positionLimit,
                entryCondition, invalidCondition, rankScore, dataConfidence, historicalHitRate, riskReward,
                evidenceSummary, blockedReasons, context, sourceQuality, signalProvenance));
        validateStageAndAction(candidateStage, action, blockedReasons);
        validateSourceQuality(candidateStage, action, sourceQuality, dataConfidence);
        validateProvenance(action, signalProvenance);
        if (dataCutoffAt.isAfter(decisionAt)) {
            throw new IllegalArgumentException("dataCutoffAt must not be after decisionAt");
        }
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
                blockedReasons, context, SourceQualityStatus.SINGLE_SOURCE, Map.of(),
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
            SignalProvenance signalProvenance
    ) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (replayPayload != null) {
            merged.putAll(replayPayload);
        }
        merged.put("strategyCode", strategyCode.name());
        merged.put("strategyVersion", strategyVersion);
        merged.put("symbol", symbol);
        merged.put("companyName", companyName);
        merged.put("decisionAt", decisionAt.toString());
        merged.put("dataCutoffAt", dataCutoffAt.toString());
        merged.put("candidateStage", candidateStage.name());
        merged.put("action", action.name());
        merged.put("rankScore", rankScore);
        merged.put("dataConfidence", dataConfidence);
        merged.put("historicalHitRate", historicalHitRate);
        merged.put("riskReward", riskReward);
        merged.put("sourceQuality", sourceQuality.name());
        merged.put("signalProvenance", signalProvenance.name());
        merged.put("evidenceSummary", evidenceSummary);
        merged.put("blockedReasons", blockedReasons);
        merged.put("context", new LinkedHashMap<>(context));
        merged.put("entryCondition", entryCondition);
        merged.put("invalidCondition", invalidCondition);
        merged.put("positionLimit", positionLimit);
        return merged;
    }

    private static Object freezeJsonValue(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return freezeJsonNumber(number);
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

    private static BigDecimal freezeJsonNumber(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw invalidReplayPayload("numeric values must be finite");
            }
            return new BigDecimal(doubleValue.toString());
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw invalidReplayPayload("numeric values must be finite");
            }
            return new BigDecimal(floatValue.toString());
        }
        throw invalidReplayPayload("unsupported numeric type: " + value.getClass().getName());
    }

    private static BigDecimal normalizeBoundedDecimal(
            BigDecimal value,
            String field,
            BigDecimal minimum,
            BigDecimal maximum,
            int scale
    ) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " must be between "
                    + minimum.toPlainString() + " and " + maximum.toPlainString());
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
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

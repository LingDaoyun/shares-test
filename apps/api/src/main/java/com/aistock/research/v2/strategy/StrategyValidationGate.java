package com.aistock.research.v2.strategy;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StrategyValidationGate {

    private static final int MIN_SAMPLE_COUNT = 60;
    private static final BigDecimal MIN_HIT_RATE = new BigDecimal("50.00");
    private static final BigDecimal MAX_DRAWDOWN = new BigDecimal("25.00");

    public StrategySignal apply(StrategySignal signal, StrategyValidationSummary summary) {
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        if (summary == null || !matches(signal, summary)) {
            return copy(signal, signal.action(), signal.candidateStage(), signal.positionLimit(),
                    signal.historicalHitRate(), "VALIDATION_MISSING", List.of("样本外验证摘要缺失，不能作为正式发布依据。"));
        }
        boolean passed = passed(summary);
        StrategyAction action = signal.action();
        CandidateStage stage = signal.candidateStage();
        BigDecimal positionLimit = signal.positionLimit();
        String status = "PASSED_OOS";
        if (!passed && isBuyLike(signal.action())) {
            action = StrategyAction.NEXT_WATCH;
            stage = CandidateStage.WATCH;
            positionLimit = BigDecimal.ZERO;
            status = "INSUFFICIENT_OOS";
        } else if (!passed) {
            status = "INSUFFICIENT_OOS";
        }
        return copy(signal, action, stage, positionLimit, summary.hitRate(), status, validationEvidence(summary, passed));
    }

    private StrategySignal copy(
            StrategySignal signal,
            StrategyAction action,
            CandidateStage stage,
            BigDecimal positionLimit,
            BigDecimal historicalHitRate,
            String validationStatus,
            List<String> validationEvidence
    ) {
        Map<String, String> context = new LinkedHashMap<>(signal.context());
        context.put("validationStatus", validationStatus);
        List<String> evidence = new ArrayList<>(signal.evidenceSummary());
        evidence.addAll(validationEvidence);
        Map<String, Object> replayPayload = new LinkedHashMap<>(signal.replayPayload());
        replayPayload.put("validationStatus", validationStatus);
        replayPayload.put("validationEvidence", validationEvidence);
        return new StrategySignal(
                signal.strategyCode(),
                signal.strategyVersion(),
                signal.symbol(),
                signal.companyName(),
                signal.decisionAt(),
                signal.dataCutoffAt(),
                stage,
                action,
                positionLimit,
                entryCondition(signal, action),
                signal.invalidCondition(),
                signal.rankScore(),
                signal.dataConfidence(),
                historicalHitRate,
                signal.riskReward(),
                evidence,
                signal.blockedReasons(),
                context,
                signal.sourceQuality(),
                replayPayload,
                signal.signalProvenance());
    }

    private String entryCondition(StrategySignal signal, StrategyAction action) {
        if (action == signal.action()) {
            return signal.entryCondition();
        }
        return "样本外验证不足，本轮仅进入观察，不发布买入动作。";
    }

    private List<String> validationEvidence(StrategyValidationSummary summary, boolean passed) {
        String prefix = passed ? "样本外验证通过" : "样本外验证不足";
        return List.of(prefix + "：窗口 " + text(summary.validationWindow())
                + "，样本 " + summary.sampleCount()
                + "，命中率 " + plain(summary.hitRate())
                + "%，最大回撤 " + plain(summary.maxDrawdown()) + "%。");
    }

    private boolean passed(StrategyValidationSummary summary) {
        return summary.sampleCount() >= MIN_SAMPLE_COUNT
                && value(summary.hitRate()).compareTo(MIN_HIT_RATE) >= 0
                && value(summary.maxDrawdown()).compareTo(MAX_DRAWDOWN) <= 0;
    }

    private boolean matches(StrategySignal signal, StrategyValidationSummary summary) {
        return signal.strategyCode() == summary.strategyCode()
                && signal.strategyVersion().equals(summary.strategyVersion());
    }

    private boolean isBuyLike(StrategyAction action) {
        return action == StrategyAction.ADD || action == StrategyAction.LIGHT_TRIAL;
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String plain(BigDecimal value) {
        return value(value).stripTrailingZeros().toPlainString();
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}

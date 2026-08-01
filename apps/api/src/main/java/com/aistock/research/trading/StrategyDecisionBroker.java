package com.aistock.research.trading;

import com.aistock.research.tradefeedback.StrategyFeedbackSummary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class StrategyDecisionBroker {

    private static final int FEEDBACK_REASON_LIMIT = 4;

    public TradingAdvice resolve(List<StrategyDecisionInput> inputs) {
        List<StrategyDecisionInput> decisions = inputs == null
                ? List.of()
                : inputs.stream().filter(item -> item != null).toList();
        if (decisions.isEmpty()) {
            return new TradingAdvice(
                    "WAIT",
                    "观望",
                    0,
                    "统一决策中枢没有收到可用策略信号。",
                    List.of("缺少策略输入"),
                    List.of("刷新策略模块后再判断")
            );
        }

        StrategyDecisionInput strongest = decisions.stream()
                .max(Comparator.comparingInt(this::actionPriority)
                        .thenComparing(StrategyDecisionInput::confidence)
                        .thenComparing(input -> input.score() == null ? BigDecimal.ZERO : input.score()))
                .orElse(decisions.get(0));
        boolean hasAdd = decisions.stream().anyMatch(this::isAddLike);
        boolean hasReview = decisions.stream().anyMatch(this::isReviewLike);
        boolean hasRiskExit = decisions.stream().anyMatch(this::isExitLike);

        if (hasRiskExit) {
            return new TradingAdvice(
                    strongest.action(),
                    textOrDefault(strongest.actionLabel(), "风险优先"),
                    confidence(decisions, -8),
                    "统一决策中枢检测到减仓或退出信号，风险动作优先。",
                    reasons(decisions),
                    mergeControls(decisions, List.of("先处理风险信号，再讨论新增仓位"))
            );
        }
        if (hasAdd && hasReview) {
            return new TradingAdvice(
                    "LIGHT_TRIAL",
                    "轻仓试错",
                    confidence(decisions, -10),
                    "策略分歧：存在买入/加仓信号，也存在补证或等待信号，本轮不直接加仓。",
                    reasons(decisions),
                    mergeControls(decisions, List.of("证据补齐前只允许轻仓试错", "若下一交易日无法继续确认，暂停追加"))
            );
        }
        if (hasAdd) {
            return new TradingAdvice(
                    "ADD",
                    "分批加仓",
                    confidence(decisions, 0),
                    "多策略支持，且没有补证/等待门禁压制。",
                    reasons(decisions),
                    mergeControls(decisions, List.of("分批执行，不一次性打满仓位", "单票仓位遵守组合上限"))
            );
        }
        if (decisions.stream().anyMatch(this::isLightTrialLike)) {
            return new TradingAdvice(
                    "LIGHT_TRIAL",
                    "轻仓试错",
                    confidence(decisions, -4),
                    "策略未形成加仓共识，但允许用很小仓位验证假设。",
                    reasons(decisions),
                    mergeControls(decisions, List.of("试错仓位不能替代正式建仓", "确认失败时退出观察"))
            );
        }
        return new TradingAdvice(
                "WAIT",
                "观望",
                confidence(decisions, -6),
                "策略没有形成可执行共识，先观察。",
                reasons(decisions),
                mergeControls(decisions, List.of("等待更多策略同向确认"))
        );
    }

    public TradingAdvice resolve(
            List<StrategyDecisionInput> inputs,
            List<StrategyFeedbackSummary> feedbackSummaries
    ) {
        TradingAdvice baseAdvice = resolve(inputs);
        List<StrategyFeedbackSummary> matchedFeedback = matchedFeedback(inputs, feedbackSummaries);
        return adjustWithFeedback(baseAdvice, matchedFeedback);
    }

    public TradingAdvice adjustWithFeedback(
            TradingAdvice baseAdvice,
            List<StrategyFeedbackSummary> matchedFeedback
    ) {
        if (baseAdvice == null) {
            return resolve(List.of());
        }
        if (matchedFeedback == null || matchedFeedback.isEmpty()) {
            return baseAdvice;
        }

        BigDecimal adjustment = conservativeAdjustment(matchedFeedback);
        int adjustedConfidence = clamp(baseAdvice.confidence() + adjustment.setScale(0, RoundingMode.HALF_UP).intValue());
        String summary = baseAdvice.summary()
                + " 历史复盘可靠性修正 " + signed(adjustment)
                + "，仅修正置信度，不直接改变买卖动作。";

        return new TradingAdvice(
                baseAdvice.action(),
                baseAdvice.actionLabel(),
                adjustedConfidence,
                summary,
                mergeText(baseAdvice.reasons(), feedbackReasons(matchedFeedback), 10),
                mergeText(baseAdvice.riskControls(), feedbackRiskControls(adjustment), 10)
        );
    }

    private boolean isAddLike(StrategyDecisionInput input) {
        return "ADD".equals(normalize(input.action()));
    }

    private boolean isLightTrialLike(StrategyDecisionInput input) {
        String action = normalize(input.action());
        return "LIGHT_TRIAL".equals(action) || "TRIAL".equals(action);
    }

    private boolean isReviewLike(StrategyDecisionInput input) {
        String action = normalize(input.action());
        if ("WAIT".equals(action)
                || "DATA_REVIEW".equals(action)
                || "EVIDENCE_REVIEW".equals(action)
                || "VALUATION_REVIEW".equals(action)
                || "WAIT_CONFIRM".equals(action)
                || "WAIT_PULLBACK".equals(action)) {
            return true;
        }
        String text = (input.actionLabel() + " " + input.reason() + " " + String.join(" ", input.riskControls()))
                .toLowerCase(Locale.ROOT);
        return text.contains("待补")
                || text.contains("补证")
                || text.contains("证据不足")
                || text.contains("等待")
                || text.contains("不加仓")
                || text.contains("不可加仓");
    }

    private boolean isExitLike(StrategyDecisionInput input) {
        String action = normalize(input.action());
        return "SELL_ALL".equals(action)
                || "SELL".equals(action)
                || "BATCH_SELL".equals(action)
                || "REDUCE".equals(action);
    }

    private int actionPriority(StrategyDecisionInput input) {
        if (isExitLike(input)) {
            return 5;
        }
        if (isAddLike(input)) {
            return 4;
        }
        if (isLightTrialLike(input)) {
            return 3;
        }
        if (isReviewLike(input)) {
            return 2;
        }
        return 1;
    }

    private int confidence(List<StrategyDecisionInput> decisions, int adjustment) {
        BigDecimal average = decisions.stream()
                .map(input -> BigDecimal.valueOf(Math.max(0, Math.min(100, input.confidence()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(decisions.size()), 0, RoundingMode.HALF_UP);
        return clamp(average.intValue() + adjustment);
    }

    private List<String> reasons(List<StrategyDecisionInput> decisions) {
        return decisions.stream()
                .sorted(Comparator.comparingInt(this::actionPriority).reversed()
                        .thenComparing(StrategyDecisionInput::confidence, Comparator.reverseOrder()))
                .map(input -> textOrDefault(input.sourceLabel(), input.sourceCode())
                        + "：" + textOrDefault(input.actionLabel(), input.action())
                        + "，" + textOrDefault(input.reason(), "未提供原因"))
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(6)
                .toList();
    }

    private List<String> mergeControls(List<StrategyDecisionInput> decisions, List<String> extraControls) {
        List<String> controls = new ArrayList<>();
        decisions.forEach(input -> controls.addAll(input.riskControls()));
        controls.addAll(extraControls);
        return controls.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private List<StrategyFeedbackSummary> matchedFeedback(
            List<StrategyDecisionInput> inputs,
            List<StrategyFeedbackSummary> feedbackSummaries
    ) {
        List<StrategyDecisionInput> decisions = inputs == null
                ? List.of()
                : inputs.stream().filter(item -> item != null).toList();
        if (decisions.isEmpty() || feedbackSummaries == null || feedbackSummaries.isEmpty()) {
            return List.of();
        }
        return feedbackSummaries.stream()
                .filter(summary -> summary != null
                        && summary.adjustmentEligible()
                        && summary.reliabilityAdjustment() != null)
                .filter(summary -> decisions.stream().anyMatch(input -> matches(input, summary)))
                .distinct()
                .sorted(Comparator.comparingInt(StrategyFeedbackSummary::sampleCount).reversed()
                        .thenComparing(StrategyFeedbackSummary::sourceModule)
                        .thenComparing(StrategyFeedbackSummary::ruleVersion))
                .limit(FEEDBACK_REASON_LIMIT)
                .toList();
    }

    private boolean matches(StrategyDecisionInput input, StrategyFeedbackSummary summary) {
        String inputModule = normalizeModule(textOrDefault(input.sourceModule(), inferredSourceModule(input.sourceCode())));
        String summaryModule = normalizeModule(summary.sourceModule());
        if (!inputModule.equals(summaryModule)) {
            return false;
        }
        String inputRule = textOrDefault(input.ruleVersion(), "");
        return inputRule.isBlank() || inputRule.equalsIgnoreCase(textOrDefault(summary.ruleVersion(), ""));
    }

    private String inferredSourceModule(String sourceCode) {
        return switch (sourceCode == null ? "" : sourceCode.trim().toLowerCase(Locale.ROOT)) {
            case "short_term", "short-term", "short" -> "SHORT_TERM";
            case "tech_tracker", "hot_tracker", "hot-tracker", "tech" -> "HOT_TRACKER";
            case "mispricing", "value", "undervalued" -> "MISPRICING";
            case "cycle", "cycle_trial", "cycle-trial" -> "CYCLE_TRIAL";
            case "daily_signal", "daily-signal", "strategy_broker" -> "DAILY_SIGNAL";
            default -> sourceCode;
        };
    }

    private BigDecimal conservativeAdjustment(List<StrategyFeedbackSummary> matchedFeedback) {
        return matchedFeedback.stream()
                .map(StrategyFeedbackSummary::reliabilityAdjustment)
                .filter(adjustment -> adjustment.signum() < 0)
                .min(BigDecimal::compareTo)
                .orElseGet(() -> matchedFeedback.stream()
                        .map(StrategyFeedbackSummary::reliabilityAdjustment)
                        .max(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO));
    }

    private List<String> feedbackReasons(List<StrategyFeedbackSummary> matchedFeedback) {
        return matchedFeedback.stream()
                .map(summary -> "历史复盘："
                        + textOrDefault(summary.sourceModule(), "UNKNOWN")
                        + "/" + textOrDefault(summary.ruleVersion(), "unknown-rule")
                        + " " + textOrDefault(summary.horizon(), "T20")
                        + " 样本 " + summary.sampleCount()
                        + "，胜率 " + percent(summary.positiveRate())
                        + "，中位收益 " + percentValue(summary.medianReturn())
                        + "，可靠性修正 " + signed(summary.reliabilityAdjustment()) + "。")
                .toList();
    }

    private List<String> feedbackRiskControls(BigDecimal adjustment) {
        if (adjustment.signum() < 0) {
            return List.of("历史复盘为负修正，本轮只降低置信度，不自动触发买入或卖出。");
        }
        if (adjustment.signum() > 0) {
            return List.of("历史复盘为正修正，本轮只提高置信度，不绕过证据和风控门禁。");
        }
        return List.of("历史复盘修正为中性，本轮保持当前策略动作。");
    }

    private List<String> mergeText(List<String> primary, List<String> secondary, int limit) {
        return java.util.stream.Stream.concat(primary.stream(), secondary.stream())
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String normalizeModule(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String signed(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        return normalized.signum() > 0 ? "+" + normalized : normalized.toString();
    }

    private String percent(BigDecimal value) {
        if (value == null) {
            return "未知";
        }
        return value.multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }

    private String percentValue(BigDecimal value) {
        if (value == null) {
            return "未知";
        }
        return value.setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }

    private String normalize(String action) {
        if (action == null) {
            return "";
        }
        return action.trim().toUpperCase(Locale.ROOT);
    }

    private String textOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value;
    }
}

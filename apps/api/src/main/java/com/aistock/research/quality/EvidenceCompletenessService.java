package com.aistock.research.quality;

import com.aistock.research.trading.TradingAdvice;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EvidenceCompletenessService {

    public EvidenceCompleteness evaluate(EvidenceCompletenessInput input) {
        EvidenceCompletenessInput safe = input == null
                ? EvidenceCompletenessInput.longTerm(false, false, false, false, false, false, false, List.of())
                : input;
        ScoreBuilder builder = new ScoreBuilder();
        if (safe.shortTerm()) {
            builder.add("实时行情", "实时行情", safe.realtimeQuote(), 15);
            builder.add("估值字段", "估值字段", safe.valuation(), 15);
            builder.add("近一年K线", "近一年K线", safe.kline(), 20);
            builder.add("近三年财报质量", "近三年财报质量", safe.financial(), 20);
            builder.add("尾盘/分时确认", "尾盘/分时确认", safe.intraday(), 20);
            builder.add("公告/定期报告反证", "公告/定期报告反证", safe.filing(), 5);
            builder.add("资金流复核", "资金流复核", safe.fundFlow(), 5);
        } else {
            builder.add("实时行情", "实时行情", safe.realtimeQuote(), 15);
            builder.add("估值字段", "估值字段", safe.valuation(), 15);
            builder.add("近一年K线", "近一年K线", safe.kline(), 10);
            builder.add("近三年财报质量", "近三年财报质量", safe.financial(), 20);
            builder.add("公告/定期报告反证", "公告/定期报告反证", safe.filing(), 20);
            builder.add("行业估值对比", "行业估值对比", safe.industryComparison(), 10);
            builder.add("资金流复核", "资金流复核", safe.fundFlow(), 10);
        }
        builder.addExplicitGaps(safe.explicitGaps());
        boolean criticalOk = safe.shortTerm()
                ? safe.realtimeQuote() && safe.valuation() && safe.kline() && safe.financial() && safe.intraday()
                : safe.realtimeQuote() && safe.valuation() && safe.financial() && safe.filing();
        boolean allowsBuy = builder.score >= 70 && criticalOk;
        String status = allowsBuy ? "ENOUGH" : builder.score >= 55 ? "PARTIAL" : "INSUFFICIENT";
        String label = switch (status) {
            case "ENOUGH" -> "证据可执行";
            case "PARTIAL" -> "证据待补";
            default -> "证据不足";
        };
        List<String> riskControls = new ArrayList<>();
        if (!allowsBuy) {
            riskControls.add("证据完整度低于买入闸门时，只能观察或小仓试错，不能加仓。");
        }
        if (!builder.missing.isEmpty()) {
            riskControls.add("补齐缺口：" + String.join("、", builder.missing.stream().limit(4).toList()));
        }
        return new EvidenceCompleteness(
                Math.min(100, builder.score),
                status,
                label,
                allowsBuy,
                builder.present.stream().distinct().toList(),
                builder.missing.stream().distinct().toList(),
                riskControls.stream().distinct().toList()
        );
    }

    public TradingAdvice gateAdvice(TradingAdvice advice, EvidenceCompleteness completeness) {
        if (advice == null || completeness == null || completeness.allowsBuy() || !"ADD".equals(advice.action())) {
            return advice;
        }
        List<String> reasons = merge(
                List.of("证据完整度 " + completeness.score() + " 分，状态：" + completeness.statusLabel()),
                advice.reasons()
        );
        List<String> controls = merge(completeness.riskControls(), advice.riskControls());
        return new TradingAdvice(
                "WAIT",
                "观望",
                Math.min(55, advice.confidence()),
                "证据完整度不足，买入建议已降级为观察；补齐关键证据后再评估是否分批加仓。",
                reasons,
                controls
        );
    }

    private List<String> merge(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private static final class ScoreBuilder {
        private int score;
        private final List<String> present = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();

        private void add(String presentLabel, String missingLabel, boolean exists, int weight) {
            if (exists) {
                score += weight;
                present.add(presentLabel);
            } else {
                missing.add(missingLabel);
            }
        }

        private void addExplicitGaps(List<String> gaps) {
            if (gaps == null) {
                return;
            }
            gaps.stream()
                    .filter(gap -> gap != null && !gap.isBlank())
                    .forEach(missing::add);
        }
    }
}

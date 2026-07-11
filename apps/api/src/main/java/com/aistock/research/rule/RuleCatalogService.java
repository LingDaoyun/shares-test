package com.aistock.research.rule;

import com.aistock.research.quality.RecommendationQuality;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class RuleCatalogService {

    private final ConcurrentMap<String, RuleDefinition> rules = new ConcurrentHashMap<>();

    public RuleCatalogService() {
        seedDefaults();
    }

    public List<RuleDefinition> listRules() {
        return rules.values().stream()
                .sorted(Comparator.comparing(RuleDefinition::ruleCode))
                .toList();
    }

    public RuleDefinition upsert(String ruleCode, RuleDefinition request) {
        if (!ruleCode.equals(request.ruleCode())) {
            throw new IllegalArgumentException("路径中的 ruleCode 与请求体不一致");
        }
        return rules.compute(ruleCode, (code, existing) -> {
            int nextVersion = existing == null ? 1 : existing.version() + 1;
            return request.withVersion(nextVersion);
        });
    }

    public List<RuleEvaluationResult> evaluateAll(FactorSnapshot snapshot, RuleEngineService engineService) {
        return listRules().stream()
                .map(rule -> engineService.evaluate(rule, snapshot))
                .toList();
    }

    private void seedDefaults() {
        Instant now = Instant.now();
        List<RuleDefinition> defaults = new ArrayList<>();
        defaults.add(new RuleDefinition(
                "ANNUAL_QUALITY_FILTER",
                "年度质量过滤",
                true,
                1,
                List.of(
                        new RuleCondition("roe_annual", Operator.GTE, new BigDecimal("0.08"), new BigDecimal("2")),
                        new RuleCondition("operating_cash_flow_per_share", Operator.GTE, BigDecimal.ZERO, new BigDecimal("2")),
                        new RuleCondition("revenue_growth", Operator.GTE, BigDecimal.ZERO, BigDecimal.ONE)
                ),
                RuleAction.PASS,
                "用最近年度年报指标做第一层质量过滤，后续会扩展为 5-10 年滚动指标。",
                now
        ));
        defaults.add(new RuleDefinition(
                "LIVE_VALUATION_MARGIN",
                "实时估值安全边际",
                true,
                1,
                List.of(
                        new RuleCondition("pe_ttm", Operator.GTE, new BigDecimal("0.01"), BigDecimal.ONE),
                        new RuleCondition("pe_ttm", Operator.LTE, new BigDecimal("80.00"), new BigDecimal("2")),
                        new RuleCondition("pb", Operator.LTE, new BigDecimal("8.00"), BigDecimal.ONE)
                ),
                RuleAction.SCORE,
                "使用实时 PE(TTM)/PB 做估值初筛，历史估值分位将在行情历史模块接入后替换。",
                now
        ));
        defaults.add(new RuleDefinition(
                "LIVE_RISK_BLOCKLIST",
                "流动性与风险排雷",
                true,
                1,
                List.of(
                        new RuleCondition("st_flag", Operator.EQ, BigDecimal.ZERO, new BigDecimal("2")),
                        new RuleCondition("turnover_rate", Operator.GTE, new BigDecimal("0.20"), BigDecimal.ONE),
                        new RuleCondition("amount", Operator.GTE, RecommendationQuality.MIN_RECOMMENDED_AMOUNT, BigDecimal.ONE)
                ),
                RuleAction.REJECT,
                "先排除 ST 与低流动性样本，公告处罚和质押风险会在公告模块接入后补充。",
                now
        ));
        defaults.add(new RuleDefinition(
                "POLICY_RELEVANCE_FILTER",
                "政策主题相关度",
                true,
                1,
                List.of(
                        new RuleCondition("policy_theme_relevance", Operator.GTE, new BigDecimal("0.60"), new BigDecimal("2"))
                ),
                RuleAction.REVIEW,
                "基于行业和公司名称关键词做主题初筛，最终要用主营收入占比和公告证据校验。",
                now
        ));

        defaults.forEach(rule -> rules.put(rule.ruleCode(), rule));
    }
}

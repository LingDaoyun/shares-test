package com.aistock.research.policy;

import com.aistock.research.integration.gov.GovPolicyClient;
import com.aistock.research.integration.gov.GovPolicyItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PolicyThemeService {

    private final GovPolicyClient govPolicyClient;

    public PolicyThemeService(GovPolicyClient govPolicyClient) {
        this.govPolicyClient = govPolicyClient;
    }

    public List<PolicyTheme> listThemes() {
        List<GovPolicyItem> policies = fetchPoliciesSafely();
        List<PolicyTheme> themes = List.of(
                new PolicyTheme(
                        "NEW_QUALITY_PRODUCTIVITY",
                        "新质生产力与高端制造",
                        "国家级",
                        "2026-2030",
                        scoreFor(policies, List.of("现代化", "科技", "制造", "产业", "设备", "应急", "安全")),
                        List.of("工业母机", "机器人核心零部件", "工业软件", "高端传感器"),
                        signalsFor(policies, "长期规划", List.of("现代化", "科技", "制造", "产业", "设备", "应急", "安全")),
                        List.of("概念拥挤", "订单兑现慢", "设备周期波动")
                ),
                new PolicyTheme(
                        "DIGITAL_INFRA",
                        "数字经济与算力基础设施",
                        "部委/地方共振",
                        "2025-2030",
                        scoreFor(policies, List.of("数字", "数据", "网络", "信息", "智能", "算力", "人工智能")),
                        List.of("算力中心", "液冷", "光模块", "数据安全", "行业应用软件"),
                        signalsFor(policies, "专项工程", List.of("数字", "数据", "网络", "信息", "智能", "算力", "人工智能")),
                        List.of("估值波动大", "技术迭代快", "海外限制")
                ),
                new PolicyTheme(
                        "GREEN_TRANSITION",
                        "绿色低碳与能源转型",
                        "国家级",
                        "长期",
                        scoreFor(policies, List.of("绿色", "低碳", "能源", "生态", "环保", "农业", "农村")),
                        List.of("储能", "电网设备", "新能源运营", "节能改造", "循环经济"),
                        signalsFor(policies, "政策兑现", List.of("绿色", "低碳", "能源", "生态", "环保", "农业", "农村")),
                        List.of("产能过剩", "价格战", "补贴退坡")
                )
        );
        return themes.stream()
                .sorted(Comparator.comparing(PolicyTheme::strengthScore).reversed())
                .toList();
    }

    public Map<String, PolicyTheme> themeIndex() {
        return listThemes().stream().collect(java.util.stream.Collectors.toMap(PolicyTheme::themeCode, theme -> theme));
    }

    private List<GovPolicyItem> fetchPoliciesSafely() {
        try {
            return govPolicyClient.fetchLatestPolicies(40);
        } catch (IllegalStateException exception) {
            return List.of(new GovPolicyItem(
                    "系统降级",
                    "fallback",
                    "中国政府网最新政策数据暂时不可用",
                    "https://www.gov.cn/zhengce/zuixin/",
                    null,
                    20
            ));
        }
    }

    private BigDecimal scoreFor(List<GovPolicyItem> policies, List<String> keywords) {
        List<GovPolicyItem> matched = policies.stream()
                .filter(policy -> containsAny(policy.title(), keywords))
                .toList();
        if (matched.isEmpty()) {
            return new BigDecimal("58.00");
        }

        Set<String> sources = matched.stream().map(GovPolicyItem::source).collect(Collectors.toSet());
        BigDecimal sourceReliability = matched.stream()
                .map(policy -> BigDecimal.valueOf(policy.sourceWeight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(matched.size()), 2, RoundingMode.HALF_UP);
        BigDecimal crossSourceBonus = BigDecimal.valueOf(Math.min(sources.size() * 4L, 16));
        BigDecimal hitBonus = BigDecimal.valueOf(Math.min(matched.size() * 3L, 18));
        BigDecimal recencyBonus = BigDecimal.valueOf(Math.min(matched.stream().filter(this::isRecent).count() * 2L, 8));
        BigDecimal score = BigDecimal.valueOf(42)
                .add(sourceReliability.multiply(new BigDecimal("0.28")))
                .add(crossSourceBonus)
                .add(hitBonus)
                .add(recencyBonus);
        return score.min(new BigDecimal("96")).setScale(2, RoundingMode.HALF_UP);
    }

    private List<PolicySignal> signalsFor(List<GovPolicyItem> policies, String signalType, List<String> keywords) {
        List<PolicySignal> matched = policies.stream()
                .filter(policy -> containsAny(policy.title(), keywords))
                .sorted(Comparator.comparing((GovPolicyItem policy) -> signalConfidence(policy, keywords)).reversed())
                .limit(6)
                .map(policy -> new PolicySignal(
                        policy.source(),
                        signalType + " / " + agentVerdict(policy, keywords),
                        policy.title(),
                        signalConfidence(policy, keywords),
                        policy.url(),
                        policy.publishedAt()
                ))
                .toList();
        if (!matched.isEmpty()) {
            return matched;
        }
        return policies.stream()
                .sorted(Comparator.comparing((GovPolicyItem policy) -> signalConfidence(policy, keywords)).reversed())
                .limit(4)
                .map(policy -> new PolicySignal(
                        policy.source(),
                        "最新政策 / 待交叉验证",
                        policy.title(),
                        Math.max(45, policy.sourceWeight() / 2),
                        policy.url(),
                        policy.publishedAt()
                ))
                .toList();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null) {
            return false;
        }
        return keywords.stream().anyMatch(text::contains);
    }

    private int signalConfidence(GovPolicyItem policy, List<String> keywords) {
        String title = policy.title() == null ? "" : policy.title();
        int keywordHits = (int) keywords.stream().filter(title::contains).count();
        int confidence = 35
                + Math.min(policy.sourceWeight() / 3, 30)
                + Math.min(keywordHits * 8, 24);
        if (isRecent(policy)) {
            confidence += 20;
        } else if (policy.publishedAt() == null || policy.publishedAt().isBlank()) {
            confidence -= 8;
        } else {
            confidence -= 12;
        }
        return Math.max(35, Math.min(confidence, 96));
    }

    private String agentVerdict(GovPolicyItem policy, List<String> keywords) {
        List<String> agents = new ArrayList<>();
        if (containsAny(policy.title(), keywords)) {
            agents.add("政策原文Agent命中");
        }
        if (policy.sourceWeight() >= 85) {
            agents.add("权威来源Agent确认");
        }
        if (isRecent(policy)) {
            agents.add("时效Agent确认");
        } else {
            agents.add("时效Agent待验证");
        }
        if (agents.isEmpty()) {
            return "待交叉验证";
        }
        return String.join("+", agents);
    }

    private boolean isRecent(GovPolicyItem policy) {
        if (policy.publishedAt() == null || policy.publishedAt().isBlank()) {
            return false;
        }
        try {
            LocalDate date = LocalDate.parse(policy.publishedAt().substring(0, Math.min(10, policy.publishedAt().length())));
            return date.isAfter(LocalDate.now().minusYears(2));
        } catch (DateTimeParseException | StringIndexOutOfBoundsException exception) {
            return false;
        }
    }
}

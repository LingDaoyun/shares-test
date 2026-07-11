package com.aistock.research.filing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class FilingEventExtractor {

    private static final List<String> RISK_KEYWORDS = List.of(
            "立案", "处罚", "监管", "问询", "诉讼", "仲裁", "质押", "减持", "担保", "冻结", "退市",
            "无法表示意见", "保留意见", "会计差错", "更正", "亏损", "商誉减值", "重大不确定性"
    );
    private static final List<String> MOAT_KEYWORDS = List.of(
            "核心技术", "自主研发", "研发投入", "专利", "技术平台", "重大合同", "中标", "订单", "高端客户",
            "客户认证", "产能", "募投项目", "市场份额"
    );
    private static final List<String> VALIDATION_KEYWORDS = List.of(
            "营业收入", "净利润", "经营活动产生的现金流量净额", "毛利率", "利润分配", "分红", "业绩",
            "合同", "中标", "项目", "验收", "交付"
    );

    public List<FilingEvent> extract(FilingDocument document, FilingTextSnapshot snapshot) {
        String text = snapshot.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<FilingEvent> events = new ArrayList<>();
        addEvent(events, document, text, "RISK", "风险事件", riskSeverity(text), RISK_KEYWORDS);
        addEvent(events, document, text, "MOAT", "壁垒线索", "MEDIUM", MOAT_KEYWORDS);
        addEvent(events, document, text, "VALIDATION", "兑现线索", "LOW", VALIDATION_KEYWORDS);
        return events.stream().limit(6).toList();
    }

    private void addEvent(
            List<FilingEvent> events,
            FilingDocument document,
            String text,
            String eventType,
            String eventLabel,
            String severity,
            List<String> keywords
    ) {
        List<String> matched = matchedKeywords(text, keywords);
        if (matched.isEmpty()) {
            return;
        }
        String evidence = evidenceSentence(text, matched);
        events.add(new FilingEvent(
                eventType,
                eventLabel,
                severity,
                document.documentId(),
                document.title(),
                evidence,
                document.sourceUrl(),
                confidence(matched.size(), eventType)
        ));
    }

    private List<String> matchedKeywords(String text, List<String> keywords) {
        Set<String> matched = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                matched.add(keyword);
            }
        }
        return matched.stream().toList();
    }

    private String evidenceSentence(String text, List<String> keywords) {
        String normalized = text.replace('\n', '。');
        String[] parts = normalized.split("[。；;.!！?？]");
        for (String keyword : keywords) {
            for (String part : parts) {
                String sentence = part.trim();
                if (sentence.contains(keyword)) {
                    return shorten(sentence);
                }
            }
        }
        return shorten(normalized);
    }

    private String shorten(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= 180) {
            return text;
        }
        return text.substring(0, 180) + "...";
    }

    private String riskSeverity(String text) {
        if (text.contains("退市") || text.contains("立案") || text.contains("无法表示意见") || text.contains("重大不确定性")) {
            return "HIGH";
        }
        if (text.contains("处罚") || text.contains("诉讼") || text.contains("质押") || text.contains("亏损")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private int confidence(int matchedCount, String eventType) {
        int base = "RISK".equals(eventType) ? 72 : 66;
        return Math.min(92, base + matchedCount * 4);
    }
}

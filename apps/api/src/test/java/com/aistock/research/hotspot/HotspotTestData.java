package com.aistock.research.hotspot;

import com.aistock.research.company.EvidenceItem;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.news.IndustryNewsItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class HotspotTestData {
    static IndustryNewsItem news(String id, Instant time) {
        return new IndustryNewsItem(id, "样本公司发布新型电池", "测试新闻源", "https://news.example/" + id,
                time.toString(), "样本公司已发布新型电池材料的测试结果");
    }

    static EastMoneyQuote quote(String symbol, String name, String price, Instant time) {
        return new EastMoneyQuote(symbol, name, "SH", "电池", new BigDecimal(price), new BigDecimal("1.5"),
                null, null, null, null, null, null, "测试行情", "https://quote.example/" + symbol,
                time, null, time);
    }

    static HotspotRecommendation stock(IndustryNewsItem source, Instant firstSeen) {
        return new HotspotRecommendation("600001", "样本公司", "电池", "主营材料与事件关联", "需要下游采购才能兑现",
                "BUSINESS_MATCH", List.of(source), List.of(new EvidenceItem("公司业务资料", "主营业务", "研发和生产电池材料",
                "https://company.example/600001", 75)), List.of("采购尚未确认"), "MEDIUM", new BigDecimal("10.00"),
                BigDecimal.ZERO, "https://quote.example/600001", firstSeen.toString(), firstSeen, new BigDecimal("10.00"), firstSeen.toString());
    }

    static HotspotEvent event(String id, List<IndustryNewsItem> sources, List<HotspotRecommendation> stocks, Instant time) {
        return new HotspotEvent(id, "新型电池事件", "技术突破", List.of("电池"), List.of("电池材料"),
                "公开发布测试结果", "需要进一步验证", 70, "MEDIUM", sources, stocks, List.of("量产存在不确定性"), time, time);
    }

    static HotspotSnapshot snapshot(List<IndustryNewsItem> news, List<HotspotEvent> events, Instant time) {
        return new HotspotSnapshot("PARTIAL", time, time, time, time.minusSeconds(1), time.minusSeconds(1),
                "已保存", news.size(), (int) events.stream().flatMap(event -> event.recommendations().stream()).count(), news, events, List.of("采集范围有限"));
    }
}

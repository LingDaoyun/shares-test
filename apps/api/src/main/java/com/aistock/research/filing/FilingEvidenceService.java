package com.aistock.research.filing;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.company.EvidenceItem;
import com.aistock.research.config.LiveDataProperties;
import com.aistock.research.integration.cninfo.CninfoAnnouncement;
import com.aistock.research.integration.cninfo.CninfoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FilingEvidenceService implements FilingEvidenceProvider {

    private static final Logger logger = LoggerFactory.getLogger(FilingEvidenceService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("Asia/Shanghai"));
    private static final List<String> MOAT_KEYWORDS = List.of(
            "研发", "专利", "核心技术", "重大合同", "中标", "订单", "客户", "认证", "产能", "募投", "股权激励"
    );
    private static final List<String> RISK_KEYWORDS = List.of(
            "风险", "处罚", "监管", "问询", "诉讼", "仲裁", "减持", "质押", "担保", "亏损", "退市", "ST",
            "更正", "会计差错", "审计意见", "冻结", "立案"
    );
    private static final List<String> VALIDATION_KEYWORDS = List.of(
            "年度报告", "年报", "半年报", "季度报告", "业绩", "利润分配", "分红", "回购", "合同", "中标",
            "验收", "项目", "投资者关系"
    );

    private final CninfoClient cninfoClient;
    private final FilingPdfTextService filingPdfTextService;
    private final FilingEventExtractor filingEventExtractor;
    private final LiveDataProperties properties;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public FilingEvidenceService(
            CninfoClient cninfoClient,
            FilingPdfTextService filingPdfTextService,
            FilingEventExtractor filingEventExtractor,
            LiveDataProperties properties
    ) {
        this.cninfoClient = cninfoClient;
        this.filingPdfTextService = filingPdfTextService;
        this.filingEventExtractor = filingEventExtractor;
        this.properties = properties;
    }

    @Override
    public FilingEvidenceSummary summarize(CompanyProfile company) {
        Instant now = Instant.now();
        CacheEntry cached = cache.get(company.symbol());
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.summary();
        }
        FilingEvidenceSummary summary = fetchOrFallback(company, now);
        cache.put(company.symbol(), new CacheEntry(summary, now.plusSeconds(600)));
        return summary;
    }

    private FilingEvidenceSummary fetchOrFallback(CompanyProfile company, Instant now) {
        int limit = filingLimit();
        try {
            List<CninfoAnnouncement> announcements = cninfoClient.fetchAnnouncements(company, limit);
            List<FilingDocument> documents = announcements.stream()
                    .map(this::toDocument)
                    .toList();
            if (!documents.isEmpty()) {
                List<FilingEvent> events = extractPdfEvents(documents);
                return summarizeDocuments(
                        company.symbol(),
                        "LIVE",
                        "巨潮实时公告",
                        documents,
                        events,
                        List.of(),
                        now
                );
            }
        } catch (IllegalStateException exception) {
            logger.warn("公告源获取失败，使用公司画像证据降级：{}，原因：{}", company.symbol(), exception.getMessage());
            logger.debug("公告源降级详情", exception);
        }
        List<FilingDocument> fallbackDocuments = fallbackDocuments(company);
        List<String> gaps = new ArrayList<>();
        gaps.add("未获取到巨潮实时公告列表，需补齐证券内部编码或交易所公告源");
        if (fallbackDocuments.isEmpty()) {
            gaps.add("公司画像中也缺少年报、公告或调研纪要证据");
        }
        return summarizeDocuments(
                company.symbol(),
                fallbackDocuments.isEmpty() ? "MISSING" : "FALLBACK",
                fallbackDocuments.isEmpty() ? "公告证据缺失" : "公司画像证据降级",
                fallbackDocuments,
                List.of(),
                gaps,
                now
        );
    }

    private FilingEvidenceSummary summarizeDocuments(
            String symbol,
            String status,
            String statusLabel,
            List<FilingDocument> documents,
            List<FilingEvent> extractedEvents,
            List<String> sourceGaps,
            Instant now
    ) {
        Set<String> moatSignals = new LinkedHashSet<>();
        Set<String> riskSignals = new LinkedHashSet<>();
        Set<String> validationSignals = new LinkedHashSet<>();
        for (FilingDocument document : documents) {
            if (containsAny(document.matchedKeywords(), MOAT_KEYWORDS)) {
                moatSignals.add(document.title());
            }
            if (containsAny(document.matchedKeywords(), RISK_KEYWORDS)) {
                riskSignals.add(document.title());
            }
            if (containsAny(document.matchedKeywords(), VALIDATION_KEYWORDS)) {
                validationSignals.add(document.title());
            }
        }
        for (FilingEvent event : extractedEvents) {
            String signal = event.documentTitle() + "：" + event.evidenceText();
            if ("MOAT".equals(event.eventType())) {
                moatSignals.add(signal);
            } else if ("RISK".equals(event.eventType())) {
                riskSignals.add(signal);
            } else if ("VALIDATION".equals(event.eventType())) {
                validationSignals.add(signal);
            }
        }
        List<String> gaps = new ArrayList<>(sourceGaps);
        if (documents.stream().noneMatch(document -> "年度报告".equals(document.category()))) {
            gaps.add("缺少最近年度报告或定期报告原文");
        }
        if ("LIVE".equals(status) && extractedEvents.isEmpty()) {
            gaps.add("已获取公告标题，但 PDF 正文暂未抽取到结构化事件");
        }
        if (moatSignals.isEmpty()) {
            gaps.add("公告中暂未识别出核心技术、合同、产能或客户认证线索");
        }
        if (validationSignals.isEmpty()) {
            gaps.add("公告中暂未识别出业绩、合同、中标或项目兑现线索");
        }
        return new FilingEvidenceSummary(
                symbol,
                status,
                statusLabel,
                documents.size(),
                parsedDocumentCount(extractedEvents),
                documents,
                extractedEvents,
                moatSignals.stream().limit(6).toList(),
                riskSignals.stream().limit(6).toList(),
                validationSignals.stream().limit(6).toList(),
                gaps.stream().distinct().toList(),
                now
        );
    }

    private List<FilingEvent> extractPdfEvents(List<FilingDocument> documents) {
        List<FilingEvent> events = new ArrayList<>();
        for (FilingDocument document : documents.stream().filter(this::shouldParsePdf).limit(pdfParseLimit()).toList()) {
            filingPdfTextService.extract(document)
                    .map(snapshot -> filingEventExtractor.extract(document, snapshot))
                    .ifPresent(events::addAll);
        }
        return events.stream().limit(12).toList();
    }

    private boolean shouldParsePdf(FilingDocument document) {
        return "年度报告".equals(document.category())
                || "业务验证".equals(document.category())
                || "风险事件".equals(document.category());
    }

    private int parsedDocumentCount(List<FilingEvent> events) {
        return (int) events.stream()
                .map(FilingEvent::documentId)
                .distinct()
                .count();
    }

    private FilingDocument toDocument(CninfoAnnouncement announcement) {
        List<String> keywords = matchedKeywords(announcement.title());
        String downloadUrl = cninfoClient.downloadUrl(announcement.adjunctUrl());
        String sourceUrl = cninfoClient.disclosureUrl(
                announcement.announcementId(),
                announcement.orgId(),
                announcement.symbol(),
                announcement.announcementTime()
        );
        return new FilingDocument(
                announcement.announcementId(),
                announcement.title(),
                "巨潮资讯",
                category(announcement.title()),
                announcement.announcementTime() <= 0 ? null : DATE_FORMATTER.format(Instant.ofEpochMilli(announcement.announcementTime())),
                sourceUrl,
                downloadUrl,
                keywords,
                confidence(keywords)
        );
    }

    private List<FilingDocument> fallbackDocuments(CompanyProfile company) {
        List<FilingDocument> documents = new ArrayList<>();
        for (EvidenceItem item : company.evidence()) {
            if (isFilingEvidence(item)) {
                List<String> keywords = matchedKeywords(item.sourceTitle() + item.excerpt());
                documents.add(new FilingDocument(
                        company.symbol() + "-" + documents.size(),
                        item.sourceTitle(),
                        item.sourceType(),
                        category(item.sourceTitle() + item.excerpt()),
                        company.financialReportDate(),
                        item.url(),
                        item.url(),
                        keywords,
                        Math.max(45, Math.min(item.confidence(), 78))
                ));
            }
        }
        return documents.stream().limit(filingLimit()).toList();
    }

    private boolean isFilingEvidence(EvidenceItem item) {
        return item.sourceType().contains("年报")
                || item.sourceType().contains("公告")
                || item.sourceType().contains("调研")
                || item.sourceTitle().contains("报告")
                || item.sourceTitle().contains("公告")
                || item.sourceTitle().contains("纪要");
    }

    private List<String> matchedKeywords(String text) {
        String value = text == null ? "" : text;
        return allKeywords().stream()
                .filter(value::contains)
                .distinct()
                .toList();
    }

    private List<String> allKeywords() {
        List<String> keywords = new ArrayList<>();
        keywords.addAll(MOAT_KEYWORDS);
        keywords.addAll(RISK_KEYWORDS);
        keywords.addAll(VALIDATION_KEYWORDS);
        return keywords.stream().distinct().toList();
    }

    private boolean containsAny(List<String> matched, List<String> keywords) {
        return matched.stream().anyMatch(keywords::contains);
    }

    private String category(String title) {
        String value = title == null ? "" : title;
        if (value.contains("年度报告") || value.contains("年报") || value.contains("半年报") || value.contains("季度报告")) {
            return "年度报告";
        }
        if (containsText(value, RISK_KEYWORDS)) {
            return "风险事件";
        }
        if (containsText(value, MOAT_KEYWORDS) || containsText(value, VALIDATION_KEYWORDS)) {
            return "业务验证";
        }
        return "常规公告";
    }

    private boolean containsText(String value, List<String> keywords) {
        return keywords.stream().anyMatch(value::contains);
    }

    private int confidence(List<String> keywords) {
        return Math.min(88, 58 + keywords.size() * 6);
    }

    private int filingLimit() {
        Integer limit = properties.filingLimit();
        if (limit == null || limit <= 0) {
            return 12;
        }
        return Math.min(limit, 30);
    }

    private int pdfParseLimit() {
        Integer limit = properties.filingPdfParseLimit();
        if (limit == null || limit <= 0) {
            return 2;
        }
        return Math.min(limit, 6);
    }

    private record CacheEntry(FilingEvidenceSummary summary, Instant expiresAt) {
    }
}

package com.aistock.research.integration.cninfo;

import com.aistock.research.company.CompanyProfile;
import com.aistock.research.config.LiveDataProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class CninfoClient {

    private static final Logger logger = LoggerFactory.getLogger(CninfoClient.class);
    private static final String CNINFO_HOST = "https://www.cninfo.com.cn/";
    private static final String STATIC_HOST = "https://static.cninfo.com.cn/";
    private static final String STOCK_LIST_URL = CNINFO_HOST + "new/data/szse_stock.json";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LiveDataProperties properties;
    private volatile Map<String, String> stockOrgIds;

    public CninfoClient(RestClient restClient, ObjectMapper objectMapper, LiveDataProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<CninfoAnnouncement> fetchAnnouncements(CompanyProfile company, int limit) {
        List<RequestCandidate> candidates = requestCandidates(company);
        Exception lastException = null;
        for (RequestCandidate candidate : candidates) {
            try {
                List<CninfoAnnouncement> announcements = doFetch(company, candidate, limit);
                if (!announcements.isEmpty()) {
                    return announcements;
                }
            } catch (Exception exception) {
                lastException = exception;
            }
        }
        if (lastException == null) {
            throw new IllegalStateException("未匹配到巨潮证券内部编码：" + company.symbol());
        }
        throw new IllegalStateException("巨潮公告获取失败：" + company.symbol(), lastException);
    }

    public String disclosureUrl(String announcementId, String orgId, String symbol, long announcementTime) {
        String date = Instant.ofEpochMilli(announcementTime).toString().substring(0, 10);
        return CNINFO_HOST + "new/disclosure/detail?stockCode=" + symbol
                + "&announcementId=" + announcementId
                + "&orgId=" + orgId
                + "&announcementTime=" + date;
    }

    public String downloadUrl(String adjunctUrl) {
        if (adjunctUrl == null || adjunctUrl.isBlank()) {
            return null;
        }
        if (adjunctUrl.startsWith("http")) {
            return adjunctUrl;
        }
        return STATIC_HOST + adjunctUrl;
    }

    private List<CninfoAnnouncement> doFetch(CompanyProfile company, RequestCandidate candidate, int limit) throws Exception {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("pageNum", "1");
        form.add("pageSize", String.valueOf(Math.max(1, Math.min(limit, 30))));
        form.add("column", candidate.column());
        form.add("tabName", "fulltext");
        form.add("plate", "");
        form.add("stock", candidate.stock());
        form.add("searchkey", "");
        form.add("secid", "");
        form.add("category", "");
        form.add("trade", "");
        form.add("seDate", "");
        form.add("sortName", "");
        form.add("sortType", "");
        form.add("isHLtitle", "true");

        String body = restClient.post()
                .uri(URI.create(properties.cninfoAnnouncementUrl()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", CNINFO_HOST.substring(0, CNINFO_HOST.length() - 1))
                .header("Referer", CNINFO_HOST + "new/commonUrl/pageOfSearch?url=disclosure/list/search")
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(body);
        JsonNode items = root.path("announcements");
        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }
        List<CninfoAnnouncement> announcements = new ArrayList<>();
        for (JsonNode item : items) {
            readAnnouncement(company, item).ifPresent(announcements::add);
        }
        return announcements;
    }

    private Optional<CninfoAnnouncement> readAnnouncement(CompanyProfile company, JsonNode item) {
        String announcementId = text(item, "announcementId");
        String title = text(item, "announcementTitle");
        if (announcementId == null || title == null) {
            return Optional.empty();
        }
        return Optional.of(new CninfoAnnouncement(
                announcementId,
                defaultText(text(item, "secCode"), company.symbol()),
                defaultText(text(item, "secName"), company.name()),
                text(item, "orgId"),
                title.replaceAll("<[^>]+>", ""),
                item.path("announcementTime").asLong(0L),
                text(item, "adjunctUrl")
        ));
    }

    private List<RequestCandidate> requestCandidates(CompanyProfile company) {
        String symbol = company.symbol();
        List<RequestCandidate> candidates = new ArrayList<>();
        resolveOrgId(symbol).ifPresent(orgId ->
                candidates.add(new RequestCandidate(column(symbol), symbol + "," + orgId)));
        if (symbol.startsWith("0") || symbol.startsWith("3")) {
            candidates.add(new RequestCandidate("szse", symbol + ",gssz0" + symbol));
        }
        if (symbol.startsWith("6")) {
            candidates.add(new RequestCandidate("sse", symbol + ",gssh0" + symbol));
            candidates.add(new RequestCandidate("sse", symbol + ",gssh" + symbol));
        }
        candidates.add(new RequestCandidate("szse", symbol));
        candidates.add(new RequestCandidate("sse", symbol));
        return candidates;
    }

    private String column(String symbol) {
        return symbol.startsWith("6") ? "sse" : "szse";
    }

    private Optional<String> resolveOrgId(String symbol) {
        Map<String, String> cached = stockOrgIds;
        if (cached == null) {
            synchronized (this) {
                cached = stockOrgIds;
                if (cached == null) {
                    cached = loadStockOrgIds();
                    stockOrgIds = cached;
                }
            }
        }
        return Optional.ofNullable(cached.get(symbol));
    }

    private Map<String, String> loadStockOrgIds() {
        try {
            String body = restClient.get()
                    .uri(URI.create(STOCK_LIST_URL))
                    .header("User-Agent", "Mozilla/5.0 AI-Stock-Research/0.1")
                    .retrieve()
                    .body(String.class);
            JsonNode items = objectMapper.readTree(body).path("stockList");
            Map<String, String> orgIds = new HashMap<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String code = text(item, "code");
                    String orgId = text(item, "orgId");
                    if (code != null && orgId != null) {
                        orgIds.put(code, orgId);
                    }
                }
            }
            return orgIds;
        } catch (Exception exception) {
            logger.warn("巨潮证券列表获取失败，将回退到证券编码猜测逻辑", exception);
            return Map.of();
        }
    }

    private String text(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record RequestCandidate(String column, String stock) {
    }
}

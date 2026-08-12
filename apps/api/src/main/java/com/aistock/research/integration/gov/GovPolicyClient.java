package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.aistock.research.configuration.RuntimeConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GovPolicyClient {

    private static final Logger logger = LoggerFactory.getLogger(GovPolicyClient.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[-年./](\\d{1,2})[-月./](\\d{1,2})");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RuntimeConfigStore runtimeConfigStore;

    public GovPolicyClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            RuntimeConfigStore runtimeConfigStore
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.runtimeConfigStore = runtimeConfigStore;
    }

    public List<GovPolicyItem> fetchLatestPolicies(int limit) {
        GovPolicyFetchResult result = fetchLatestPoliciesWithStatus(limit);
        if (result.items().isEmpty()) {
            throw new IllegalStateException("所有政策源均获取失败");
        }
        return result.items();
    }

    public GovPolicyFetchResult fetchLatestPoliciesWithStatus(int limit) {
        Map<String, GovPolicyItem> items = new LinkedHashMap<>();
        List<String> failedSources = new ArrayList<>();
        List<PolicySourceConfig> sources = List.copyOf(runtimeConfigStore.readPolicySources());
        int perSourceLimit = Math.max(8, (int) Math.ceil(limit / (double) Math.max(sources.size(), 1)));
        for (PolicySourceConfig source : sources) {
            try {
                for (GovPolicyItem item : fetchFromSource(source, perSourceLimit)) {
                    items.putIfAbsent(dedupKey(item), item);
                }
            } catch (Exception exception) {
                logger.warn("政策源获取失败：{}", source.name(), exception);
                failedSources.add(source.name() + "：" + rootMessage(exception));
            }
        }
        return new GovPolicyFetchResult(
                selectDiversePolicies(new ArrayList<>(items.values()), sources, limit),
                failedSources
        );
    }

    private List<GovPolicyItem> fetchFromSource(PolicySourceConfig source, int limit) {
        String body = restClient.get()
                .uri(URI.create(source.url()))
                .header("User-Agent", "Mozilla/5.0 AI-Stock-Research/0.1")
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        if ("json".equalsIgnoreCase(source.type()) || source.url().endsWith(".json")) {
            return parseJsonSource(source, body, limit);
        }
        return parseHtmlSource(source, body, limit);
    }

    private List<GovPolicyItem> parseJsonSource(PolicySourceConfig source, String body, int limit) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<GovPolicyItem> items = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (items.size() >= limit) {
                        break;
                    }
                    String title = firstText(item, "TITLE", "title", "name");
                    String url = firstText(item, "URL", "url", "href");
                    String publishedAt = firstText(item, "DOCRELPUBTIME", "PUBTIME", "date", "publishedAt");
                    if (title != null && url != null) {
                        items.add(new GovPolicyItem(
                                source.name(),
                                source.type(),
                                cleanTitle(title),
                                normalizeUrl(source.url(), url),
                                normalizeDate(publishedAt),
                                sourceWeight(source)
                        ));
                    }
                }
            }
            return items;
        } catch (Exception exception) {
            throw new IllegalStateException(source.name() + " JSON 政策数据解析失败", exception);
        }
    }

    private List<GovPolicyItem> parseHtmlSource(PolicySourceConfig source, String body, int limit) {
        Document document = Jsoup.parse(body, source.url());
        List<GovPolicyItem> items = new ArrayList<>();
        for (Element link : document.select("a[href]")) {
            if (items.size() >= limit) {
                break;
            }
            String title = cleanTitle(link.text());
            String href = link.attr("abs:href");
            if (!looksLikePolicyTitle(title) || href == null || href.isBlank() || href.startsWith("javascript:")) {
                continue;
            }
            String nearbyText = link.parent() == null ? title : link.parent().text();
            items.add(new GovPolicyItem(
                    source.name(),
                    source.type(),
                    title,
                    normalizeUrl(source.url(), href),
                    extractDate(nearbyText),
                    sourceWeight(source)
            ));
        }
        return items;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private boolean looksLikePolicyTitle(String title) {
        if (title == null || title.length() < 8 || title.length() > 120) {
            return false;
        }
        if (title.contains("政府信息公开指南")
                || title.contains("政府信息公开制度")
                || title.contains("政府信息公开目录")
                || title.contains("政府信息公开年报")
                || title.contains("网站地图")
                || title.contains("联系我们")
                || title.contains("领导信箱")
                || title.contains("检索")
                || title.contains("搜索")
                || title.contains("登录")) {
            return false;
        }
        return title.contains("规划")
                || title.contains("方案")
                || title.contains("意见")
                || title.contains("通知")
                || title.contains("办法")
                || title.contains("政策")
                || title.contains("目录")
                || title.contains("指南")
                || title.contains("公告");
    }

    private List<GovPolicyItem> selectDiversePolicies(
            List<GovPolicyItem> items,
            List<PolicySourceConfig> sources,
            int limit
    ) {
        Map<String, List<GovPolicyItem>> bySource = new LinkedHashMap<>();
        for (PolicySourceConfig source : sources) {
            bySource.put(source.name(), new ArrayList<>());
        }
        for (GovPolicyItem item : items) {
            bySource.computeIfAbsent(item.source(), ignored -> new ArrayList<>()).add(item);
        }

        List<GovPolicyItem> selected = new ArrayList<>();
        int index = 0;
        while (selected.size() < limit) {
            boolean added = false;
            for (List<GovPolicyItem> sourceItems : bySource.values()) {
                if (index < sourceItems.size()) {
                    selected.add(sourceItems.get(index));
                    added = true;
                    if (selected.size() >= limit) {
                        return selected;
                    }
                }
            }
            if (!added) {
                break;
            }
            index++;
        }

        if (selected.size() < limit) {
            List<GovPolicyItem> remaining = items.stream()
                    .filter(item -> !selected.contains(item))
                    .sorted(java.util.Comparator.comparing(GovPolicyItem::sourceWeight).reversed())
                    .limit(limit - selected.size())
                    .toList();
            selected.addAll(remaining);
        }
        return selected;
    }

    private String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        return title.replaceAll("\\s+", " ").trim();
    }

    private String normalizeUrl(String baseUrl, String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return URI.create(baseUrl).resolve(url).toString();
    }

    private String extractDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + "-" + pad(matcher.group(2)) + "-" + pad(matcher.group(3));
    }

    private String normalizeDate(String text) {
        String extracted = extractDate(text);
        return extracted == null ? text : extracted;
    }

    private String pad(String value) {
        return value.length() == 1 ? "0" + value : value;
    }

    private int sourceWeight(PolicySourceConfig source) {
        return source.weight() <= 0 ? 60 : source.weight();
    }

    private String dedupKey(GovPolicyItem item) {
        String title = item.title() == null ? "" : item.title();
        String url = item.url() == null ? "" : item.url();
        return URLEncoder.encode(title + "|" + url, StandardCharsets.UTF_8);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}

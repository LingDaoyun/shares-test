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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GovPolicyClient {

    private static final Logger logger = LoggerFactory.getLogger(GovPolicyClient.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[-年./](\\d{1,2})[-月./](\\d{1,2})");
    private static final int MAX_BODY_CHARS = 40_000;
    private static final List<String> ARTICLE_SELECTORS = List.of(".TRS_Editor", ".view.TRS_UEDITOR", ".TRS_UEDITOR", "#UCAP-CONTENT", "#con_con", "#zoom",
            "#zoomcon", ".pages_content", "#article-content", ".article-content", ".article_content",
            ".content-article", ".xxgk_content", ".bt_content", "article");

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
        PageCache pages = new PageCache();
        List<PolicySourceConfig> sources = List.copyOf(runtimeConfigStore.readPolicySources());
        int perSourceLimit = Math.max(8, (int) Math.ceil(limit / (double) Math.max(sources.size(), 1)));
        for (PolicySourceConfig source : sources) {
            try {
                List<GovPolicyItem> fetched = fetchFromSource(source, perSourceLimit, pages);
                if (fetched.isEmpty()) failedSources.add(source.name() + "：未解析到政策或产业新闻，来源覆盖待核验");
                for (GovPolicyItem item : fetched) {
                    items.merge(dedupKey(item), item, (first, next) ->
                            next.planningBaseline() || first.bodyText() == null && next.bodyText() != null ? next : first);
                }
            } catch (Exception exception) {
                logger.warn("政策源获取失败：{} ({})", source.name(), exception.getClass().getSimpleName());
                failedSources.add(source.name() + (isDocumentSource(source.type())
                        ? "：正文采集失败或未识别可靠正文" : "：政策来源采集失败"));
            }
        }
        return new GovPolicyFetchResult(
                enrichPlanningBodies(selectDiversePolicies(new ArrayList<>(items.values()), sources, limit), pages, failedSources),
                failedSources
        );
    }

    private List<GovPolicyItem> fetchFromSource(PolicySourceConfig source, int limit, PageCache pages) {
        String body = fetchBody(source.url(), pages);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        if (isDocumentSource(source.type())) {
            GovPolicyItem item = parseDocument(source, body, null, null);
            if (item == null) throw new IllegalStateException("No reliable article body");
            return List.of(item);
        }
        if ("json".equalsIgnoreCase(source.type()) || source.url().endsWith(".json")) {
            return parseJsonSource(source, body, limit);
        }
        return parseHtmlSource(source, body, limit);
    }

    private boolean isDocumentSource(String type) {
        return "document".equalsIgnoreCase(type) || "plan".equalsIgnoreCase(type);
    }

    private static final class PageCache {
        private final Map<String, String> bodies = new LinkedHashMap<>();
        private final Set<String> attempted = new HashSet<>();
    }

    private String fetchBody(String url, PageCache pages) {
        if (pages.bodies.containsKey(url)) return pages.bodies.get(url);
        if (!pages.attempted.add(url)) throw new IllegalStateException("Page already failed this collection");
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported article URL");
        }
        ResponseEntity<byte[]> response = restClient.get().uri(uri).header("User-Agent", "Mozilla/5.0 AI-Stock-Research/0.1")
                .retrieve().toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length > 4_000_000) throw new IllegalStateException("Invalid policy response size");
        MediaType contentType = response.getHeaders().getContentType();
        String charset = contentType == null || contentType.getCharset() == null ? null : contentType.getCharset().name();
        String body = new String(bytes, charset == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(charset));
        if (!body.stripLeading().startsWith("[") && !body.stripLeading().startsWith("{")) {
            try {
                // Jsoup detects the HTML charset when a server omits the HTTP charset (common on government sites).
                body = Jsoup.parse(new ByteArrayInputStream(bytes), charset, url).outerHtml();
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot decode policy article", exception);
            }
        }
        pages.bodies.put(url, body);
        return body;
    }

    private List<GovPolicyItem> enrichPlanningBodies(List<GovPolicyItem> selected, PageCache pages, List<String> gaps) {
        List<GovPolicyItem> result = new ArrayList<>();
        int attempts = 0;
        boolean capped = false;
        for (GovPolicyItem item : selected) {
            if (item.bodyText() != null || isDocumentSource(item.sourceType()) || !item.title().contains("规划")) {
                result.add(item);
                continue;
            }
            if (attempts >= 4) {
                capped = true;
                result.add(item);
                continue;
            }
            attempts++;
            try {
                GovPolicyItem enriched = parseDocument(new PolicySourceConfig(item.source(), item.sourceType(), item.url(), item.sourceWeight()),
                        fetchBody(item.url(), pages), item.title(), item.publishedAt());
                if (enriched == null) throw new IllegalStateException("No reliable article body");
                result.add(enriched);
            } catch (Exception exception) {
                result.add(item);
                gaps.add(item.source() + "：规划正文未补全，已保留标题线索。");
            }
        }
        if (capped) gaps.add("正文采集：规划正文补抓达到4篇上限，其余标题尚无正文。");
        return List.copyOf(result);
    }

    private GovPolicyItem parseDocument(PolicySourceConfig source, String html, String fallbackTitle, String fallbackDate) {
        Document document = Jsoup.parse(html, source.url());
        String text = articleText(document);
        if (text == null) return null;
        String title = metaContent(document, Set.of("articletitle", "dc.title", "og:title"));
        if (title == null) {
            Element heading = document.selectFirst("h1");
            if (heading != null) title = cleanTitle(heading.text());
        }
        if (title == null || title.isBlank()) title = fallbackTitle;
        if (title == null || title.isBlank()) title = cleanTitle(document.title());
        if (title == null || title.isBlank()) return null;
        String published = publicationDate(document);
        return new GovPolicyItem(source.name(), source.type(), title, source.url(),
                published == null ? fallbackDate : published, sourceWeight(source), text, "plan".equalsIgnoreCase(source.type()));
    }

    private String articleText(Document document) {
        for (String selector : ARTICLE_SELECTORS) {
            Element original = document.selectFirst(selector);
            if (original == null) continue;
            Element content = original.clone();
            content.select("script,style,nav,header,footer,form,button,iframe,noscript,.related,.related-links,.share,.breadcrumb,.nav,.pagination,.copyright").remove();
            if ("article".equals(selector) && content.select("p").isEmpty()) continue;
            content.select("p,div,section,h1,h2,h3,h4,h5,h6,li,tr,br").forEach(block -> block.appendText("\n"));
            String text = content.wholeText().replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                    .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
            if (text.length() < 20) continue;
            if (text.length() > MAX_BODY_CHARS) {
                String suffix = "\n[正文超过采集上限，后续内容未收录]";
                text = text.substring(0, MAX_BODY_CHARS - suffix.length()) + suffix;
            }
            return text;
        }
        return null;
    }

    private String metaContent(Document document, Set<String> keys) {
        for (Element meta : document.select("meta")) {
            String key = meta.hasAttr("name") ? meta.attr("name") : meta.attr("property");
            String value = meta.attr("content").trim();
            if (keys.contains(key.toLowerCase(Locale.ROOT)) && !value.isBlank()) return value;
        }
        return null;
    }

    private String publicationDate(Document document) {
        String meta = metaContent(document, Set.of("pubdate", "publishdate", "pubtime", "dc.date.issued",
                "article:published_time", "datepublished", "publication_date"));
        String parsed = reliableDate(meta);
        if (parsed != null) return parsed;
        for (Element time : document.select("time[itemprop=datePublished],.pubtime,#pubtime,#con_time,#NewsArticlePubDay,.publish-time,.article-date,.publish-date")) {
            parsed = reliableDate(time.hasAttr("datetime") ? time.attr("datetime") : time.text());
            if (parsed != null) return parsed;
        }
        return null;
    }

    private String reliableDate(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try { OffsetDateTime.parse(text.replace(' ', 'T')); return text; } catch (RuntimeException ignored) { }
        try { LocalDateTime.parse(text.replace(' ', 'T')); return text; } catch (RuntimeException ignored) { }
        String date = extractDate(text);
        try { if (date != null) return LocalDate.parse(date).toString(); } catch (RuntimeException ignored) { }
        return null;
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
                                publishedAt == null ? null : publishedAt.trim(),
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
            items.add(new GovPolicyItem(
                    source.name(),
                    source.type(),
                    title,
                    normalizeUrl(source.url(), href),
                    listPublicationDate(link),
                    sourceWeight(source)
            ));
        }
        return items;
    }

    private String listPublicationDate(Element link) {
        Element parent = link.parent();
        if (parent == null || Set.of("body", "html", "nav", "header", "footer").contains(parent.tagName())
                || parent.select("a[href]").size() != 1 || parent.text().length() > 400) return null;
        Element nearby = parent.clone();
        nearby.select("a,nav,header,footer,script,style").remove();
        Element time = nearby.selectFirst("time[datetime]");
        return reliableDate(time == null ? nearby.text() : time.attr("datetime"));
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
                || title.contains("公告")
                || looksLikeIndustrialNews(title);
    }

    private boolean looksLikeIndustrialNews(String title) {
        boolean industry = List.of("产业", "制造", "工业", "消费", "以旧换新", "农业", "农产品", "粮食",
                        "能源", "天然气", "电力", "电网", "储能", "光伏", "风电", "煤炭", "石化",
                        "汽车", "交通", "物流", "铁路", "航空", "航天", "半导体", "芯片", "算力", "人工智能",
                        "数据中心", "医药", "医疗", "创新药", "住房", "房地产", "城市更新", "金融", "资本市场",
                        "旅游", "稀土", "有色金属", "矿产", "机器人", "通信", "软件", "电池", "固态", "电解质")
                .stream().anyMatch(title::contains);
        boolean development = List.of("投资", "项目", "建设", "投产", "开工", "落地", "订单", "资金",
                        "补贴", "下达", "增长", "运行", "产量", "产能", "突破", "试点", "推进", "支持", "发布", "启动")
                .stream().anyMatch(title::contains);
        return industry && development;
    }

    private List<GovPolicyItem> selectDiversePolicies(
            List<GovPolicyItem> items,
            List<PolicySourceConfig> sources,
            int limit
    ) {
        if (limit <= 0) return List.of();
        List<GovPolicyItem> selected = new ArrayList<>(items.stream().filter(GovPolicyItem::planningBaseline).limit(limit).toList());
        if (selected.size() >= limit) return selected;
        Map<String, List<GovPolicyItem>> bySource = new LinkedHashMap<>();
        for (PolicySourceConfig source : sources) {
            bySource.put(source.name(), new ArrayList<>());
        }
        for (GovPolicyItem item : items) {
            if (item.planningBaseline()) continue;
            bySource.computeIfAbsent(item.source(), ignored -> new ArrayList<>()).add(item);
        }

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

}

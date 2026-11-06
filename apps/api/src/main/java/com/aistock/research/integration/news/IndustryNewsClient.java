package com.aistock.research.integration.news;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Collects bounded, dated RSS evidence; relevance and sector discovery belong to downstream analysis. */
@Component
public class IndustryNewsClient {

    public static final String COVERAGE_GAP = "新闻覆盖：仅采集已配置 RSS 的最新条目并限量选择，不代表全网、全部行业或来源完整历史；摘要来自 RSS，未采集文章全文。";
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SOURCE_ENTRIES = 300;
    private static final int MAX_SOURCE_ITEMS = 60;
    private static final Duration LOOKBACK = Duration.ofDays(7);
    private static final DateTimeFormatter NUMERIC_OFFSET_DATE_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss xx", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private final RestClient restClient;
    private final List<Source> sources;

    public IndustryNewsClient(
            RestClient restClient,
            @Value("${research.hotspot.news.kr36-newsflash-url:https://www.36kr.com/feed-newsflash}") String newsflashUrl,
            @Value("${research.hotspot.news.kr36-article-url:https://www.36kr.com/feed-article}") String articleUrl,
            @Value("${research.hotspot.news.ithome-url:https://www.ithome.com/rss/}") String ithomeUrl
    ) {
        // Reuses the application's bounded connection/read timeouts; no article or model-supplied URLs are fetched.
        this.restClient = restClient;
        this.sources = List.of(new Source("36氪快讯", newsflashUrl), new Source("36氪文章", articleUrl),
                new Source("IT之家", ithomeUrl));
    }

    public IndustryNewsFetchResult fetchRecent(int limit) {
        if (limit <= 0) return new IndustryNewsFetchResult(List.of(), List.of());
        Set<String> gaps = new LinkedHashSet<>(List.of(COVERAGE_GAP));
        List<List<IndustryNewsItem>> perSource = new ArrayList<>();
        Instant now = Instant.now();
        for (Source source : sources) {
            if (source.url() == null || source.url().isBlank()) {
                gaps.add(source.name() + "：RSS 未配置或已停用，本次无该来源覆盖。");
                continue;
            }
            URI feedUri = httpUri(source.url(), null);
            if (feedUri == null) {
                gaps.add(source.name() + "：RSS 地址无效，本次无该来源覆盖。");
                continue;
            }
            try {
                Document feed = fetchFeed(feedUri);
                List<IndustryNewsItem> items = parseFeed(source, feedUri, feed, now, gaps);
                if (items.isEmpty()) gaps.add(source.name() + "：未取得近 7 天内带可靠发布时间的新闻，本次无有效近期覆盖。");
                perSource.add(items);
            } catch (RuntimeException exception) {
                // Never expose remote response bodies, configured URLs, or exception details to the analysis prompt/UI.
                gaps.add(source.name() + "：RSS 获取或解析失败，本次无该来源覆盖。");
            }
        }
        int boundedLimit = Math.min(limit, sources.size() * MAX_SOURCE_ITEMS);
        List<IndustryNewsItem> selected = selectBalanced(perSource, boundedLimit);
        if (perSource.stream().mapToInt(List::size).sum() > selected.size()) {
            gaps.add("新闻选择：重复报道已合并，并按来源均衡与数量上限选择，部分 RSS 条目未纳入本次分析。");
        }
        return new IndustryNewsFetchResult(selected, List.copyOf(gaps));
    }

    private Document fetchFeed(URI uri) {
        return restClient.get().uri(uri)
                .header("Accept", "application/rss+xml,application/atom+xml,application/xml,text/xml")
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()
                            || response.getHeaders().getContentLength() > MAX_FEED_BYTES) {
                        throw new IllegalStateException("RSS response unavailable");
                    }
                    byte[] bytes = response.getBody().readNBytes(MAX_FEED_BYTES + 1);
                    if (bytes.length == 0 || bytes.length > MAX_FEED_BYTES) {
                        throw new IllegalStateException("RSS response empty or too large");
                    }
                    // Jsoup's XML parser does not resolve or fetch external entities.
                    return Jsoup.parse(new ByteArrayInputStream(bytes), null, uri.toString(), Parser.xmlParser());
                });
    }

    private List<IndustryNewsItem> parseFeed(Source source, URI feedUri, Document feed, Instant now, Set<String> gaps) {
        Element root = feed == null ? null : feed.children().first();
        String rootName = root == null ? "" : localName(root);
        if (!Set.of("rss", "rdf", "feed").contains(rootName)) {
            gaps.add(source.name() + "：返回内容不是可识别的 RSS/Atom，来源覆盖待补充。");
            return List.of();
        }
        List<Element> entries = root.getAllElements().stream()
                .filter(element -> "item".equals(localName(element)) || "entry".equals(localName(element))).toList();
        if (entries.isEmpty()) gaps.add(source.name() + "：RSS 为空或未包含可识别新闻条目。");
        if (entries.size() > MAX_SOURCE_ENTRIES) gaps.add(source.name() + "：RSS 超过单次解析上限，部分条目未采集。");
        List<IndustryNewsItem> candidates = new ArrayList<>();
        for (Element entry : entries.subList(0, Math.min(entries.size(), MAX_SOURCE_ENTRIES))) {
            Instant published = parsePublishedAt(childText(entry, "pubdate", "published", "date"));
            if (published == null) {
                gaps.add(source.name() + "：部分条目缺少可解析的原始发布时间，已排除。");
                continue;
            }
            if (published.isAfter(now)) {
                gaps.add(source.name() + "：部分条目发布时间晚于当前时间，已排除。");
                continue;
            }
            if (published.isBefore(now.minus(LOOKBACK))) {
                gaps.add(source.name() + "：部分条目超过近 7 天窗口，未作为近期新闻纳入。");
                continue;
            }
            String title = plainText(childText(entry, "title"), 300);
            URI articleUri = httpUri(articleLink(entry), feedUri);
            if (title.isBlank() || normalizedTitle(title).isBlank() || articleUri == null) {
                gaps.add(source.name() + "：部分条目缺少有效标题或原始链接，已排除。");
                continue;
            }
            String url = articleUri.toString();
            String id = UUID.nameUUIDFromBytes(canonicalUrl(url).getBytes(StandardCharsets.UTF_8)).toString();
            candidates.add(new IndustryNewsItem(id, title, source.name(), url, published.toString(),
                    plainText(childText(entry, "description", "summary"), 800)));
        }
        candidates.sort(Comparator.comparing((IndustryNewsItem item) -> Instant.parse(item.publishedAt())).reversed());
        Set<String> urls = new HashSet<>();
        Set<String> titles = new HashSet<>();
        List<IndustryNewsItem> items = new ArrayList<>();
        for (IndustryNewsItem candidate : candidates) {
            String url = canonicalUrl(candidate.url());
            String title = normalizedTitle(candidate.title());
            if (urls.contains(url) || titles.contains(title)) continue;
            urls.add(url);
            titles.add(title);
            if (items.size() < MAX_SOURCE_ITEMS) items.add(candidate);
            else gaps.add(source.name() + "：近期新闻超过单源数量上限，部分条目未纳入分析。");
        }
        return items;
    }

    private List<IndustryNewsItem> selectBalanced(List<List<IndustryNewsItem>> perSource, int limit) {
        List<IndustryNewsItem> selected = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        Set<String> titles = new HashSet<>();
        int[] positions = new int[perSource.size()];
        boolean added = true;
        while (selected.size() < limit && added) {
            added = false;
            for (int sourceIndex = 0; sourceIndex < perSource.size() && selected.size() < limit; sourceIndex++) {
                List<IndustryNewsItem> items = perSource.get(sourceIndex);
                while (positions[sourceIndex] < items.size()) {
                    IndustryNewsItem item = items.get(positions[sourceIndex]++);
                    String url = canonicalUrl(item.url());
                    String title = normalizedTitle(item.title());
                    if (urls.contains(url) || titles.contains(title)) continue;
                    urls.add(url);
                    titles.add(title);
                    selected.add(item);
                    added = true;
                    break;
                }
            }
        }
        return selected;
    }

    private static Instant parsePublishedAt(String value) {
        if (value == null || value.isBlank()) return null;
        String date = value.trim();
        try { return Instant.parse(date); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(date).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
        catch (DateTimeParseException ignored) { }
        // 36kr provides an original numeric offset, e.g. "2026-10-19 17:41:06  +0800".
        try { return OffsetDateTime.parse(date.replaceAll("\\s+", " "), NUMERIC_OFFSET_DATE_TIME).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static String articleLink(Element entry) {
        for (Element child : entry.children()) {
            if (!"link".equals(localName(child))) continue;
            if (child.hasAttr("href")) {
                String rel = child.attr("rel");
                if (rel.isBlank() || "alternate".equalsIgnoreCase(rel)) return child.attr("href");
            } else if (!child.text().isBlank()) return child.text();
        }
        for (Element child : entry.children()) {
            if ("guid".equals(localName(child)) && !"false".equalsIgnoreCase(child.attr("isPermaLink"))) return child.text();
        }
        return "";
    }

    private static String childText(Element entry, String... names) {
        for (String name : names) {
            for (Element child : entry.children()) {
                if (name.equals(localName(child)) && !child.text().isBlank()) return child.text();
            }
        }
        return "";
    }

    private static String localName(Element element) {
        String name = element.tagName().toLowerCase(Locale.ROOT);
        return name.substring(name.indexOf(':') + 1);
    }

    private static String plainText(String html, int maxLength) {
        Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
        document.select("script,style,noscript,template").remove();
        String text = document.text().replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("\\s+", " ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private static String normalizedTitle(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static URI httpUri(String value, URI base) {
        if (value == null || value.isBlank() || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value.trim());
            if (base != null) uri = base.resolve(uri);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            String url = uri.normalize().toString();
            int fragment = url.indexOf('#');
            return URI.create(fragment < 0 ? url : url.substring(0, fragment));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String canonicalUrl(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        String port = uri.getPort() < 0 || uri.getPort() == 80 || uri.getPort() == 443 ? "" : ":" + uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : String.join("&", Arrays.stream(uri.getRawQuery().split("&"))
                .filter(part -> !part.toLowerCase(Locale.ROOT).matches("(?:utm_[^=]*|spm|from|hmsr|hmpl)=.*"))
                .sorted().toList());
        return "https://" + host + port + path + (query.isEmpty() ? "" : "?" + query);
    }

    private record Source(String name, String url) { }
}

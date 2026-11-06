package com.aistock.research.integration.news;

import com.aistock.research.news.FinancialNewsFetchResult;
import com.aistock.research.news.FinancialNewsItem;
import com.aistock.research.news.NewsSourceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded adapters for public news lists. Never fetches article URLs or executes website scripts. */
@Component
public class FinancialNewsClient {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_ROWS = 300;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration LOOKBACK = Duration.ofDays(30);
    private static final Pattern DATE = Pattern.compile(
            "^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mmXXX");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<Source> sources;

    public FinancialNewsClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            @Value("${research.financial-news.eastmoney-url:https://roll.eastmoney.com/}") String eastmoneyUrl,
            @Value("${research.financial-news.cls-url:https://www.cls.cn/api/cache?rn=20&name=telegraph}") String clsUrl,
            @Value("${research.financial-news.pbc-url:https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/index.html}") String pbcUrl,
            @Value("${research.financial-news.csrc-url:https://www.csrc.gov.cn/searchList/a1a078ee0bc54721ab6b148884c784a8?_isAgg=true&_isJson=true&_pageSize=18&_template=index&_rangeTimeGte=&_channelName=&page=1}") String csrcUrl,
            @Value("${research.financial-news.nfra-url:https://www.nfra.gov.cn/cn/static/data/DocInfo/SelectItemAndDocByItemUUId/data_orderBy=item,pageSize=7.json}") String nfraUrl,
            @Value("${research.financial-news.mof-url:https://www.mof.gov.cn/zhengwuxinxi/caizhengxinwen/}") String mofUrl,
            @Value("${research.financial-news.safe-url:https://www.safe.gov.cn/safe/whxw/index.html}") String safeUrl
    ) {
        // The shared RestClient bounds connection/read time to 5/12 seconds; redirects are not followed.
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.sources = List.of(
                new Source("eastmoney", "东方财富", "MEDIA", eastmoneyUrl, "https://roll.eastmoney.com/"),
                new Source("cls", "财联社", "MEDIA", clsUrl, "https://www.cls.cn/telegraph"),
                new Source("pbc", "中国人民银行", "OFFICIAL", pbcUrl, "https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/index.html"),
                new Source("csrc", "中国证监会", "OFFICIAL", csrcUrl, "https://www.csrc.gov.cn/csrc/c100028/common_xq_list.shtml"),
                new Source("nfra", "国家金融监督管理总局", "OFFICIAL", nfraUrl, "https://www.nfra.gov.cn/cn/view/pages/index/index.html"),
                new Source("mof", "财政部", "OFFICIAL", mofUrl, "https://www.mof.gov.cn/zhengwuxinxi/caizhengxinwen/"),
                new Source("safe", "国家外汇管理局", "OFFICIAL", safeUrl, "https://www.safe.gov.cn/safe/whxw/index.html"));
    }

    public FinancialNewsFetchResult fetch(String category, Instant now) {
        if (!Set.of("MEDIA", "OFFICIAL").contains(category)) throw new IllegalArgumentException("Unknown news category");
        List<FinancialNewsItem> items = new ArrayList<>();
        List<NewsSourceStatus> statuses = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (Source source : sources) {
            if (!source.category().equals(category)) continue;
            URI requestUri = httpUri(source.url(), null);
            if (requestUri == null) {
                statuses.add(status(source, "FAILED", 0, now, "来源地址未配置或无效。"));
                continue;
            }
            try {
                if (source.id().equals("cls")) {
                    // This parameter is used by the public telegraph page; zero returns an empty historic cursor.
                    requestUri = URI.create(requestUri + (requestUri.getRawQuery() == null ? "?" : "&") + "lastTime=" + now.getEpochSecond());
                }
                byte[] body = fetchBody(requestUri);
                List<FinancialNewsItem> parsed = switch (source.id()) {
                    case "cls", "csrc", "nfra" -> parseJson(source, requestUri, body, now);
                    default -> parseHtml(source, requestUri, body, now);
                };
                List<FinancialNewsItem> unique = deduplicate(parsed);
                for (FinancialNewsItem item : unique) if (urls.add(canonicalUrl(item.url()))) items.add(item);
                statuses.add(status(source, unique.isEmpty() ? "EMPTY" : "READY", unique.size(), now,
                        unique.isEmpty() ? "来源已响应，未取得近30天内带有效标题、原文链接和发布日期的消息。"
                                : "已读取公开列表；来源名表示采集栏目，单源最多100条；摘要来自原始列表，无标题电报以原文摘录（…）展示，未采集全文。"));
            } catch (IOException | RuntimeException exception) {
                // Do not disclose remote bodies, configured credentials, or low-level diagnostics to the UI/model.
                statuses.add(status(source, "FAILED", 0, now, "公开来源获取或解析失败，暂未取得有效更新。"));
            }
        }
        items.sort(Comparator.comparing((FinancialNewsItem item) -> sortTime(item.publishedAt())).reversed());
        return new FinancialNewsFetchResult(List.copyOf(items), List.copyOf(statuses));
    }

    private byte[] fetchBody(URI uri) {
        return restClient.get().uri(uri).header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful() || response.getHeaders().getContentLength() > MAX_BYTES) {
                        throw new IllegalStateException("News source unavailable");
                    }
                    byte[] body = response.getBody().readNBytes(MAX_BYTES + 1);
                    if (body.length == 0 || body.length > MAX_BYTES) throw new IllegalStateException("Invalid response size");
                    return body;
                });
    }

    private List<FinancialNewsItem> parseHtml(Source source, URI base, byte[] body, Instant now) throws IOException {
        Document document = Jsoup.parse(new ByteArrayInputStream(body), null, base.toString());
        String containerSelector = switch (source.id()) {
            case "eastmoney" -> "#artList";
            case "pbc" -> ".newslist_style, input[name=article_paging_list_hidden]";
            case "mof" -> ".xwfb_listbox";
            case "safe" -> ".list_conr";
            default -> throw new IllegalArgumentException("Unsupported source");
        };
        if (document.select(containerSelector).isEmpty()) throw new IllegalStateException("News list structure changed");
        String rowsSelector = switch (source.id()) {
            case "eastmoney" -> "#artList li";
            case "pbc" -> "td:has(> .newslist_style)";
            case "mof" -> ".xwfb_listbox li";
            default -> ".list_conr li";
        };
        List<FinancialNewsItem> result = new ArrayList<>();
        for (Element row : document.select(rowsSelector).stream().limit(MAX_ROWS).toList()) {
            Element link = row.selectFirst(source.id().equals("eastmoney") ? "a[title][href]" : "a[href]");
            Element date = row.selectFirst(source.id().equals("safe") ? "dd" : source.id().equals("pbc") ? "span.hui12" : "span");
            if (link == null || date == null) continue;
            String title = link.hasAttr("title") ? link.attr("title") : link.text();
            add(result, source, base, title, link.attr("href"), parseDate(date.text(), now), "");
        }
        return result;
    }

    private List<FinancialNewsItem> parseJson(Source source, URI base, byte[] body, Instant now) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        List<FinancialNewsItem> result = new ArrayList<>();
        switch (source.id()) {
            case "cls" -> {
                if (root.path("errno").asInt(-1) != 0) throw new IllegalStateException("Telegraph unavailable");
                JsonNode rows = requireArray(root.path("data").path("roll_data"));
                for (int i = 0; i < Math.min(rows.size(), MAX_ROWS); i++) {
                    JsonNode row = rows.get(i);
                    String id = row.path("id").asText();
                    if (!id.matches("[1-9]\\d*")) continue;
                    String date = epochDate(row.path("ctime"), now);
                    String summary = plainText(row.path("brief").asText(""), 800);
                    if (summary.isBlank()) summary = plainText(row.path("content").asText(""), 800);
                    String title = plainText(row.path("title").asText(""), 300);
                    if (title.isBlank()) title = excerptTitle(summary);
                    add(result, source, base, title, "https://www.cls.cn/detail/" + id, date, summary);
                }
            }
            case "csrc" -> {
                JsonNode rows = requireArray(root.path("data").path("results"));
                for (int i = 0; i < Math.min(rows.size(), MAX_ROWS); i++) {
                    JsonNode row = rows.get(i);
                    add(result, source, base, row.path("title").asText(""), row.path("url").asText(""),
                            parseDate(row.path("publishedTimeStr").asText(""), now), row.path("memo").asText(""));
                }
            }
            case "nfra" -> {
                if (root.path("rptCode").asInt(-1) != 200) throw new IllegalStateException("Regulator list unavailable");
                JsonNode groups = requireArray(root.path("data"));
                int count = 0;
                for (JsonNode group : groups) {
                    JsonNode rows = requireArray(group.path("docInfoVOList"));
                    for (JsonNode row : rows) {
                        if (++count > MAX_ROWS) break;
                        String id = row.path("docId").asText();
                        String itemId = group.path("itemId").asText();
                        if (!id.matches("[1-9]\\d*") || !itemId.matches("[1-9]\\d*")) continue;
                        String url = "1".equals(row.path("isTitleLink").asText()) ? row.path("titleLink").asText("")
                                : "https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=" + id + "&itemId=" + itemId;
                        String title = row.path("docSubtitle").asText("");
                        if (title.isBlank()) title = row.path("docTitle").asText("");
                        add(result, source, base, title, url, parseDate(row.path("publishDate").asText(""), now),
                                row.path("docSummary").asText(""));
                    }
                    if (count > MAX_ROWS) break;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported source");
        }
        return result;
    }

    private static JsonNode requireArray(JsonNode node) {
        if (!node.isArray()) throw new IllegalStateException("News list structure changed");
        return node;
    }

    private static void add(List<FinancialNewsItem> items, Source source, URI base, String rawTitle,
                            String rawUrl, String publishedAt, String rawSummary) {
        String title = plainText(rawTitle, 300);
        URI url = httpUri(rawUrl, base);
        if (title.isBlank() || normalizedTitle(title).isBlank() || url == null || publishedAt == null) return;
        // Official listings sometimes syndicate media; retain only links to government originals.
        if (source.category().equals("OFFICIAL") && !url.getHost().toLowerCase(Locale.ROOT).endsWith(".gov.cn")) return;
        String id = UUID.nameUUIDFromBytes(canonicalUrl(url.toString()).getBytes(StandardCharsets.UTF_8)).toString();
        items.add(new FinancialNewsItem(id, source.category(), title, source.name(), url.toString(), publishedAt,
                plainText(rawSummary, 800)));
    }

    private static List<FinancialNewsItem> deduplicate(List<FinancialNewsItem> items) {
        items.sort(Comparator.comparing((FinancialNewsItem item) -> sortTime(item.publishedAt())).reversed());
        Set<String> urls = new HashSet<>();
        Set<String> titles = new HashSet<>();
        List<FinancialNewsItem> unique = new ArrayList<>();
        for (FinancialNewsItem item : items) {
            String url = canonicalUrl(item.url());
            String title = normalizedTitle(item.title());
            if (urls.contains(url) || titles.contains(title)) continue;
            urls.add(url);
            titles.add(title);
            unique.add(item);
            if (unique.size() == MAX_ITEMS) break;
        }
        return unique;
    }

    private static String parseDate(String value, Instant now) {
        if (value == null) return null;
        Matcher matcher = DATE.matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            if (matcher.group(4) == null) {
                LocalDate today = now.atZone(SHANGHAI).toLocalDate();
                return date.isAfter(today) || date.isBefore(today.minusDays(30)) ? null : date.toString();
            }
            LocalDateTime dateTime = date.atTime(Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                    matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6)));
            var zoned = dateTime.atZone(SHANGHAI);
            if (!recent(zoned.toInstant(), now)) return null;
            return zoned.format(matcher.group(6) == null ? MINUTE : SECOND);
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    private static String epochDate(JsonNode value, Instant now) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) return null;
        try {
            Instant date = Instant.ofEpochSecond(value.longValue());
            return recent(date, now) ? SECOND.format(date.atZone(SHANGHAI)) : null;
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static boolean recent(Instant value, Instant now) { return !value.isAfter(now) && !value.isBefore(now.minus(LOOKBACK)); }
    private static Instant sortTime(String date) {
        return date.length() == 10 ? LocalDate.parse(date).atStartOfDay(SHANGHAI).toInstant() : OffsetDateTime.parse(date).toInstant();
    }

    private static NewsSourceStatus status(Source source, String status, int count, Instant now, String message) {
        return new NewsSourceStatus(source.id(), source.name(), source.category(), source.pageUrl(), status, count,
                now, status.equals("FAILED") ? null : now, message);
    }

    private static String plainText(String html, int maximum) {
        Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
        document.select("script,style,noscript,template").remove();
        String value = document.text().replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("\\s+", " ").trim();
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private static String excerptTitle(String originalText) {
        if (originalText.isBlank()) return "";
        int end = Math.min(originalText.length(), 120);
        for (int i = 0; i < end; i++) {
            if ("。！？!?".indexOf(originalText.charAt(i)) >= 0) {
                end = i + 1;
                break;
            }
        }
        return originalText.substring(0, end) + "…";
    }

    private static String normalizedTitle(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static URI httpUri(String value, URI base) {
        if (value == null || value.isBlank() || value.trim().startsWith("#") || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value.trim());
            if (base != null) uri = base.resolve(uri);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            String normalized = uri.normalize().toString();
            int fragment = normalized.indexOf('#');
            return URI.create(fragment < 0 ? normalized : normalized.substring(0, fragment));
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

    private record Source(String id, String name, String category, String url, String pageUrl) { }
}

package com.aistock.research.integration.gov;

import com.aistock.research.configuration.PolicySourceConfig;
import com.aistock.research.configuration.RuntimeConfigStore;
import com.aistock.research.policy.PolicySourceInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Clock;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final ZoneId POLICY_ZONE = ZoneId.of("Asia/Shanghai");
    public static final int RESEARCH_LOOKBACK_DAYS = 730;
    public static final String HISTORY_COVERAGE_GAP = "历史回溯：近2年为采集范围，不代表已覆盖全部历史报道；首次采集及未开放历史页的来源仍需补充。";
    public static final String HISTORY_PAGE_LIMIT_GAP = "历史回溯：单源历史分页达到6页上限，尚有历史页面未采集。";
    public static final String HISTORY_PAGE_FAILURE_GAP = "历史回溯：部分历史页面采集失败，已保留成功采集与归档内容。";
    public static final String HISTORY_PAGE_LOOP_GAP = "历史回溯：检测到重复分页链接，已停止循环访问。";
    public static final String HISTORY_PAGINATION_UNSUPPORTED_GAP = "历史回溯：部分来源的历史分页规则无法可靠识别，尚未补齐历史页面。";
    public static final String HISTORY_ITEM_LIMIT_GAP = "历史回溯：文章数量达到采集或归档上限，已保留近期与历史样本。";
    public static final String HISTORY_ARCHIVE_READ_GAP = "历史回溯：本地历史归档暂时无法读取，当前仅使用本次成功采集内容。";
    public static final String HISTORY_ARCHIVE_WRITE_GAP = "历史回溯：本次内容暂未写入本地历史归档，后续需重新采集。";
    public static final String RESEARCH_BODY_LIMIT_GAP = "正文采集：政策报告正文补抓达到8篇上限，其余标题尚无正文。";
    public static final String RESEARCH_BODY_FAILURE_GAP = "正文采集：部分政策报告正文未补全，已保留标题线索。";
    private static final int RESEARCH_PAGE_LIMIT = 6;
    private static final int RESEARCH_SOURCE_LIMIT = 600;
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[-年./](\\d{1,2})[-月./](\\d{1,2})");
    private static final Pattern URL_MONTH_PATTERN = Pattern.compile("/(20\\d{2})[-/]?(0[1-9]|1[0-2])(?:[-/]?([0-2]\\d|3[01]))?(?:/|_)");
    private static final List<String> ARTICLE_SELECTORS = List.of(".TRS_Editor", ".view.TRS_UEDITOR", ".TRS_UEDITOR", "#UCAP-CONTENT", "#con_con", "#zoom",
            "#zoomcon", ".pages_content", "#article-content", ".article-content", ".article_content",
            ".content-article", ".xxgk_content", ".bt_content", "article");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RuntimeConfigStore runtimeConfigStore;
    private final Clock clock;
    private final GovPolicyArchiveRepository archive;

    @Autowired
    public GovPolicyClient(RestClient restClient, ObjectMapper objectMapper, RuntimeConfigStore runtimeConfigStore,
                           GovPolicyArchiveRepository archive) {
        this(restClient, objectMapper, runtimeConfigStore, Clock.system(POLICY_ZONE), archive);
    }

    public GovPolicyClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            RuntimeConfigStore runtimeConfigStore
    ) {
        this(restClient, objectMapper, runtimeConfigStore, Clock.system(POLICY_ZONE));
    }

    GovPolicyClient(RestClient restClient, ObjectMapper objectMapper, RuntimeConfigStore runtimeConfigStore, Clock clock) {
        this(restClient, objectMapper, runtimeConfigStore, clock, null);
    }

    GovPolicyClient(RestClient restClient, ObjectMapper objectMapper, RuntimeConfigStore runtimeConfigStore,
                    Clock clock, GovPolicyArchiveRepository archive) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.runtimeConfigStore = runtimeConfigStore;
        this.clock = clock;
        this.archive = archive;
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
        int perSourceLimit = Math.max(40, limit);
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

    /** Research collection has a separate history budget; trading-strategy callers keep the old 90-day path. */
    public GovPolicyFetchResult fetchResearchPoliciesWithStatus(int limit) {
        if (limit <= 0) return new GovPolicyFetchResult(List.of(), List.of());
        List<PolicySourceConfig> sources = List.copyOf(runtimeConfigStore.readPolicySources());
        if (sources.isEmpty()) return new GovPolicyFetchResult(List.of(), List.of());
        List<String> gaps = new ArrayList<>(List.of(HISTORY_COVERAGE_GAP));
        PageCache pages = new PageCache();
        Map<String, GovPolicyItem> items = new LinkedHashMap<>();
        Map<String, List<GovPolicyItem>> perSource = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(clock.withZone(POLICY_ZONE));
        for (PolicySourceConfig source : sources) {
            String sourceKey = GovPolicyArchiveRepository.sourceKey(source);
            Map<String, GovPolicyItem> collected = new LinkedHashMap<>();
            if (archive != null) {
                try {
                    List<GovPolicyItem> saved = archive.read(sourceKey, today.minusDays(RESEARCH_LOOKBACK_DAYS), today);
                    if (saved.size() >= GovPolicyArchiveRepository.MAX_SOURCE_ITEMS) gaps.add(HISTORY_ITEM_LIMIT_GAP);
                    saved.stream().filter(this::isResearchCandidate).map(item -> withCurrentSource(item, source))
                            .forEach(item -> mergeResearchItem(collected, item));
                    // A verified article date must survive a later title-only listing or failed body request.
                    List<GovPolicyItem> excluded = archive.readExcluded(sourceKey);
                    if (excluded.size() >= GovPolicyArchiveRepository.MAX_EXCLUDED_ITEMS) gaps.add(HISTORY_ITEM_LIMIT_GAP);
                    excluded.stream().map(item -> withCurrentSource(item, source))
                            .forEach(item -> mergeResearchItem(collected, item));
                } catch (RuntimeException exception) { gaps.add(HISTORY_ARCHIVE_READ_GAP); }
            }
            try {
                List<GovPolicyItem> fetched = fetchResearchSource(source, pages, gaps);
                if (fetched.isEmpty()) gaps.add(source.name() + "：未解析到政策或产业新闻，来源覆盖待核验");
                fetched.forEach(item -> mergeResearchItem(collected, item));
            } catch (RuntimeException exception) {
                gaps.add(source.name() + (isDocumentSource(source.type())
                        ? "：正文采集失败或未识别可靠正文" : "：政策来源采集失败"));
            }
            List<GovPolicyItem> eligible = collected.values().stream().filter(this::isResearchCandidate).toList();
            List<GovPolicyItem> retained = selectResearchPolicies(eligible, sources,
                    GovPolicyArchiveRepository.MAX_SOURCE_ITEMS);
            if (eligible.size() > retained.size()) gaps.add(HISTORY_ITEM_LIMIT_GAP);
            perSource.put(sourceKey, retained);
            retained.forEach(item -> mergeResearchItem(items, item));
        }
        List<GovPolicyItem> selected = selectResearchPolicies(new ArrayList<>(items.values()), sources, limit);
        if (items.size() > selected.size()) gaps.add(HISTORY_ITEM_LIMIT_GAP);
        Map<String, GovPolicyItem> invalidated = new LinkedHashMap<>();
        List<GovPolicyItem> enriched = enrichResearchBodies(selected, pages, gaps, invalidated);
        Map<String, GovPolicyItem> enrichedByUrl = new LinkedHashMap<>();
        enriched.forEach(item -> enrichedByUrl.put(item.url(), item));
        // Save every bounded source sample, including rows outside the displayed pool. Failed collection never deletes history.
        if (archive != null) for (PolicySourceConfig source : sources) {
            String key = GovPolicyArchiveRepository.sourceKey(source);
            List<GovPolicyItem> saved = perSource.getOrDefault(key, List.of()).stream()
                    .filter(item -> !invalidated.containsKey(item.url()))
                    .map(item -> withCurrentSource(enrichedByUrl.getOrDefault(item.url(), item), source))
                    .filter(this::isResearchCandidate).toList();
            try {
                List<GovPolicyItem> excluded = perSource.getOrDefault(key, List.of()).stream()
                        .map(item -> invalidated.get(item.url())).filter(java.util.Objects::nonNull)
                        .map(item -> withCurrentSource(item, source)).toList();
                if (!excluded.isEmpty()) archive.exclude(key, excluded);
                archive.upsert(key, saved, today.minusDays(RESEARCH_LOOKBACK_DAYS), today);
            }
            catch (RuntimeException exception) { gaps.add(HISTORY_ARCHIVE_WRITE_GAP); }
        }
        return new GovPolicyFetchResult(enriched, gaps.stream().distinct().toList());
    }

    private GovPolicyItem withCurrentSource(GovPolicyItem item, PolicySourceConfig source) {
        return new GovPolicyItem(source.name(), source.type(), item.title(), item.url(), item.publishedAt(),
                sourceWeight(source), item.bodyText(), "plan".equalsIgnoreCase(source.type()), item.attributedSource());
    }

    private void mergeResearchItem(Map<String, GovPolicyItem> items, GovPolicyItem item) {
        items.merge(item.url(), item, (previous, current) -> current.bodyText() != null || previous.bodyText() == null ? current : previous);
    }

    private List<GovPolicyItem> fetchResearchSource(PolicySourceConfig source, PageCache pages, List<String> gaps) {
        String body = fetchBody(source.url(), pages);
        if (body == null || body.isBlank()) return List.of();
        if (isDocumentSource(source.type())) {
            GovPolicyItem item = parseDocument(source, body, null, null);
            if (item == null) throw new IllegalStateException("No reliable article body");
            return isResearchCandidate(item) ? List.of(item) : List.of();
        }
        Map<String, GovPolicyItem> collected = new LinkedHashMap<>();
        if ("json".equalsIgnoreCase(source.type()) || source.url().endsWith(".json")) {
            parseJsonSource(source, body).stream().filter(this::isResearchCandidate).forEach(item -> mergeResearchItem(collected, item));
        } else {
            List<String> pending = new ArrayList<>(List.of(source.url()));
            Set<String> visited = new HashSet<>();
            for (int index = 0; index < pending.size() && visited.size() < RESEARCH_PAGE_LIMIT; index++) {
                String url = pending.get(index);
                if (!visited.add(url)) continue;
                try {
                    String page = index == 0 ? body : fetchBody(url, pages);
                    PolicySourceConfig pageSource = new PolicySourceConfig(source.name(), source.type(), url, source.weight());
                    parseHtmlSource(pageSource, page, true).stream().filter(this::isResearchCandidate)
                            .forEach(item -> mergeResearchItem(collected, item));
                    List<String> observedPages = new ArrayList<>(observedHistoryPages(source.url(), url, page));
                    GovHistoryPagination.Result generated = GovHistoryPagination.discover(source.url(), url, page,
                            scriptUrl -> fetchBody(scriptUrl, pages));
                    observedPages.addAll(generated.nextPages());
                    if (generated.unrecognized()) gaps.add(HISTORY_PAGINATION_UNSUPPORTED_GAP);
                    for (String next : observedPages.stream().distinct().toList()) {
                        if (visited.contains(next)) {
                            // Back/first links are normal; only a next link back to an already visited page is a cycle.
                            if (isNextLinkTo(page, url, next)) gaps.add(HISTORY_PAGE_LOOP_GAP);
                        } else if (!pending.contains(next)) pending.add(next);
                    }
                } catch (RuntimeException exception) { gaps.add(HISTORY_PAGE_FAILURE_GAP); }
            }
            if (pending.stream().anyMatch(url -> !visited.contains(url))) gaps.add(HISTORY_PAGE_LIMIT_GAP);
        }
        if (collected.size() > RESEARCH_SOURCE_LIMIT) gaps.add(HISTORY_ITEM_LIMIT_GAP);
        return selectResearchPolicies(new ArrayList<>(collected.values()), List.of(source), RESEARCH_SOURCE_LIMIT);
    }

    private List<String> observedHistoryPages(String configuredUrl, String currentUrl, String html) {
        URI configured = URI.create(configuredUrl);
        String configuredPath = configured.getPath();
        String directory = configuredPath.substring(0, configuredPath.lastIndexOf('/') + 1);
        Document doc = Jsoup.parse(html, currentUrl);
        List<String> links = new ArrayList<>();
        for (Element link : doc.select("a[href]")) {
            String label = link.text().replaceAll("\\s+", "");
            boolean next = "next".equalsIgnoreCase(link.attr("rel")) || label.matches("(?i)(下一页|下页|后一页|next|nextpage|[>»›]+)");
            boolean numbered = label.matches("\\d{1,4}") && link.closest(".pagination,.pages,.page,.pager,.paging,#pages,#page") != null;
            if ((!next && !numbered) || link.closest("header,footer,.breadcrumb") != null) continue;
            try {
                URI candidate = URI.create(link.absUrl("href")).normalize();
                String path = candidate.getPath();
                if (!java.util.Objects.equals(configured.getScheme(), candidate.getScheme())
                        || !java.util.Objects.equals(configured.getHost(), candidate.getHost())
                        || configured.getPort() != candidate.getPort() || candidate.getUserInfo() != null
                        || candidate.getFragment() != null || path == null || !path.startsWith(directory)) continue;
                // A real pagination link must remain in the configured list's file family or query endpoint.
                String originalFile = configuredPath.substring(directory.length());
                String file = path.substring(directory.length());
                String family = originalFile.replaceFirst("_\\d+(?=\\.)", "");
                boolean sameEndpoint = path.equals(configuredPath) && candidate.getQuery() != null;
                boolean sameFamily = file.replaceFirst("_\\d+(?=\\.)", "").equals(family)
                        && (file.matches(".*_\\d+\\.[^.]+") || path.equals(configuredPath));
                if (originalFile.isEmpty() && file.matches("(?i)index(?:_\\d+)?\\.html?")) sameFamily = true;
                if (!sameEndpoint && !sameFamily) continue;
                links.add(candidate.toString());
            } catch (IllegalArgumentException ignored) { }
        }
        return links.stream().distinct().toList();
    }

    private boolean isNextLinkTo(String html, String pageUrl, String target) {
        return Jsoup.parse(html, pageUrl).select("a[href]").stream().anyMatch(link -> target.equals(link.absUrl("href"))
                && ("next".equalsIgnoreCase(link.attr("rel")) || link.text().replaceAll("\\s+", "").matches("(?i)(下一页|下页|后一页|next|nextpage|[>»›]+)")));
    }

    private List<GovPolicyItem> selectResearchPolicies(List<GovPolicyItem> items, List<PolicySourceConfig> sources, int limit) {
        if (limit <= 0) return List.of();
        List<GovPolicyItem> selected = new ArrayList<>(items.stream().filter(GovPolicyItem::planningBaseline).limit(limit).toList());
        List<GovPolicyItem> history = items.stream().filter(item -> !item.planningBaseline() && isHistorical(item)).toList();
        List<GovPolicyItem> recent = items.stream().filter(item -> !item.planningBaseline() && !isHistorical(item)).toList();
        int historyBudget = Math.min(history.size(), Math.max(0, (limit - selected.size()) / 2));
        selected.addAll(roundRobinSources(history, sources, historyBudget));
        selected.addAll(roundRobinSources(recent, sources, limit - selected.size()));
        if (selected.size() < limit) selected.addAll(roundRobinSources(history.stream().filter(item -> !selected.contains(item)).toList(),
                sources, limit - selected.size()));
        return selected.stream().sorted(candidateOrder()).toList();
    }

    private List<GovPolicyItem> roundRobinSources(List<GovPolicyItem> items, List<PolicySourceConfig> sources, int limit) {
        Map<String, List<GovPolicyItem>> groups = new LinkedHashMap<>();
        sources.forEach(source -> groups.put(source.name(), new ArrayList<>()));
        items.stream().sorted(candidateOrder()).forEach(item -> groups.computeIfAbsent(item.source(), ignored -> new ArrayList<>()).add(item));
        List<GovPolicyItem> result = new ArrayList<>();
        for (int i = 0; result.size() < limit; i++) {
            boolean added = false;
            for (List<GovPolicyItem> group : groups.values()) if (i < group.size()) {
                result.add(group.get(i)); added = true;
                if (result.size() >= limit) break;
            }
            if (!added) break;
        }
        return result;
    }

    private boolean isHistorical(GovPolicyItem item) {
        LocalDate day = publicationDay(item.publishedAt());
        return day != null && day.isBefore(LocalDate.now(clock.withZone(POLICY_ZONE)).minusDays(90));
    }

    private boolean isResearchCandidate(GovPolicyItem item) {
        LocalDate today = LocalDate.now(clock.withZone(POLICY_ZONE));
        LocalDate day = publicationDay(item.publishedAt());
        if (day != null && day.isAfter(today)) return false;
        if (item.publishedAt() != null) {
            try { if (OffsetDateTime.parse(item.publishedAt().replace(' ', 'T')).toInstant().isAfter(clock.instant())) return false; }
            catch (RuntimeException ignored) { }
            try { if (LocalDateTime.parse(item.publishedAt().replace(' ', 'T')).atZone(POLICY_ZONE).toInstant().isAfter(clock.instant())) return false; }
            catch (RuntimeException ignored) { }
        }
        if (item.planningBaseline()) return true;
        if (day != null) return !day.isBefore(today.minusDays(RESEARCH_LOOKBACK_DAYS));
        if (item.url() != null) try {
            Matcher month = URL_MONTH_PATTERN.matcher(URI.create(item.url()).getPath());
            if (month.find()) {
                YearMonth value = YearMonth.of(Integer.parseInt(month.group(1)), Integer.parseInt(month.group(2)));
                if (value.atEndOfMonth().isBefore(today.minusDays(RESEARCH_LOOKBACK_DAYS)) || value.atDay(1).isAfter(today)) return false;
            }
        } catch (RuntimeException ignored) { }
        return true;
    }

    private List<GovPolicyItem> enrichResearchBodies(List<GovPolicyItem> selected, PageCache pages, List<String> gaps, Map<String, GovPolicyItem> invalidated) {
        List<GovPolicyItem> candidates = selected.stream().filter(item -> item.bodyText() == null && !isDocumentSource(item.sourceType()))
                .filter(item -> List.of("规划", "报告", "意见", "方案", "纲要").stream().anyMatch(item.title()::contains))
                .sorted(Comparator.comparingInt((GovPolicyItem item) -> PolicySourceInfo.from(item.url(), null).authorityRank()).reversed()
                        .thenComparing((GovPolicyItem item) -> !isHistorical(item)).thenComparing(candidateOrder())).toList();
        Set<GovPolicyItem> chosen = new HashSet<>(candidates.stream().limit(8).toList());
        if (candidates.size() > chosen.size()) gaps.add(RESEARCH_BODY_LIMIT_GAP);
        List<GovPolicyItem> result = new ArrayList<>();
        for (GovPolicyItem item : selected) {
            if (!chosen.contains(item)) { result.add(item); continue; }
            try {
                GovPolicyItem enriched = parseDocument(new PolicySourceConfig(item.source(), item.sourceType(), item.url(), item.sourceWeight()),
                        fetchBody(item.url(), pages), item.title(), item.publishedAt());
                if (enriched == null) throw new IllegalStateException("No reliable article body");
                if (isResearchCandidate(enriched)) result.add(enriched);
                else invalidated.put(item.url(), enriched);
            } catch (RuntimeException exception) { result.add(item); gaps.add(RESEARCH_BODY_FAILURE_GAP); }
        }
        return List.copyOf(result);
    }

    private List<GovPolicyItem> fetchFromSource(PolicySourceConfig source, int limit, PageCache pages) {
        String body = fetchBody(source.url(), pages);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        if (isDocumentSource(source.type())) {
            GovPolicyItem item = parseDocument(source, body, null, null);
            if (item == null) throw new IllegalStateException("No reliable article body");
            return isRecentCandidate(item) ? List.of(item) : List.of();
        }
        if ("json".equalsIgnoreCase(source.type()) || source.url().endsWith(".json")) {
            return recentCandidates(parseJsonSource(source, body), limit);
        }
        return recentCandidates(parseHtmlSource(source, body), limit);
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
        boolean javascript = uri.getPath().endsWith(".js") || contentType != null
                && (contentType.getSubtype().contains("javascript") || contentType.getSubtype().contains("ecmascript"));
        if (!javascript && !body.stripLeading().startsWith("[") && !body.stripLeading().startsWith("{")) {
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
        Set<GovPolicyItem> bodyCandidates = new HashSet<>(selected.stream()
                .filter(item -> item.bodyText() == null && !isDocumentSource(item.sourceType()) && item.title().contains("规划"))
                .sorted(Comparator.comparingInt((GovPolicyItem item) -> PolicySourceInfo.from(item.url(), null).authorityRank()).reversed()
                        .thenComparing(Comparator.comparingInt((GovPolicyItem item) ->
                                Math.min(item.sourceWeight(), PolicySourceInfo.from(item.url(), null).authorityRank())).reversed())
                        .thenComparing(candidateOrder()))
                .limit(4).toList());
        boolean capped = false;
        for (GovPolicyItem item : selected) {
            if (item.bodyText() != null || isDocumentSource(item.sourceType()) || !item.title().contains("规划")) {
                result.add(item);
                continue;
            }
            if (!bodyCandidates.contains(item)) {
                capped = true;
                result.add(item);
                continue;
            }
            try {
                GovPolicyItem enriched = parseDocument(new PolicySourceConfig(item.source(), item.sourceType(), item.url(), item.sourceWeight()),
                        fetchBody(item.url(), pages), item.title(), item.publishedAt());
                if (enriched == null) throw new IllegalStateException("No reliable article body");
                // A verified article date supersedes list metadata; an old article is not a new catalyst.
                if (isRecentCandidate(enriched)) result.add(enriched);
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
                published == null ? fallbackDate : published, sourceWeight(source), text, "plan".equalsIgnoreCase(source.type()),
                attributedSource(document));
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
            // fetchBody already bounds the whole response to 4 MB. Preserve later chapters for matching.
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
                "article:published_time", "datepublished", "publication_date", "firstpublishedtime"));
        String parsed = reliableDate(meta);
        if (parsed != null) return parsed;
        for (Element time : document.select("time[itemprop=datePublished],.pubtime,#pubtime,#con_time,#NewsArticlePubDay,.publish-time,.article-date,.publish-date")) {
            parsed = reliableDate(time.hasAttr("datetime") ? time.attr("datetime") : time.text());
            if (parsed != null) return parsed;
        }
        return null;
    }

    private String attributedSource(Document document) {
        String meta = metaContent(document, Set.of("contentsource", "articlesource", "source", "dc.source", "来源"));
        if (meta != null) return meta;
        Element articleHeader = document.selectFirst(".pages-date");
        if (articleHeader == null) return null;
        Element header = articleHeader.clone();
        header.select(".pages_print,.share,script,style").remove();
        Matcher source = Pattern.compile("(?:来源|稿源)\\s*[:：]\\s*(.{1,160})$").matcher(header.text());
        return source.find() ? source.group(1).trim() : null;
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

    private List<GovPolicyItem> parseJsonSource(PolicySourceConfig source, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<GovPolicyItem> items = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
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

    private List<GovPolicyItem> parseHtmlSource(PolicySourceConfig source, String body) {
        return parseHtmlSource(source, body, false);
    }

    private List<GovPolicyItem> parseHtmlSource(PolicySourceConfig source, String body, boolean research) {
        Document document = Jsoup.parse(body, source.url());
        List<GovPolicyItem> items = new ArrayList<>();
        for (Element link : document.select("a[href]")) {
            String title = cleanTitle(link.text());
            String href = link.attr("abs:href");
            boolean researchReport = research && title != null && title.length() >= 8 && title.length() <= 120
                    && (title.contains("报告") || title.contains("纲要"))
                    && List.of("政府信息公开", "检索", "搜索", "登录", "网站地图").stream().noneMatch(title::contains);
            if ((!looksLikePolicyTitle(title) && !researchReport) || !isArticleLink(link, href)) {
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

    private boolean isArticleLink(Element link, String href) {
        if (href == null || href.isBlank() || !(href.startsWith("https://") || href.startsWith("http://"))) return false;
        if (link.attr("href").startsWith("#")) return false;
        if (link.closest("nav,header,footer,.nav,.navigation,.breadcrumb") != null) return false;
        try {
            String path = URI.create(href).getPath();
            // Section roots and index pages are navigation, even when their labels contain “公告” or “政策”.
            return path != null && !path.endsWith("/")
                    && !path.matches("(?i).*/(?:index(?:_\\d+)?|list(?:_\\d+)?)\\.(?:s?html?|jsp)$");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private List<GovPolicyItem> recentCandidates(List<GovPolicyItem> items, int limit) {
        Map<String, GovPolicyItem> unique = new LinkedHashMap<>();
        items.stream().filter(this::isRecentCandidate).forEach(item -> unique.putIfAbsent(dedupKey(item), item));
        return unique.values().stream().sorted(candidateOrder()).limit(limit).toList();
    }

    private Comparator<GovPolicyItem> candidateOrder() {
        return Comparator.comparing((GovPolicyItem item) -> publicationDay(item.publishedAt()),
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private LocalDate publicationDay(String value) {
        String valid = reliableDate(value);
        if (valid == null) return null;
        try { return OffsetDateTime.parse(valid.replace(' ', 'T')).atZoneSameInstant(POLICY_ZONE).toLocalDate(); }
        catch (RuntimeException ignored) { }
        String date = extractDate(valid);
        try { return date == null ? null : LocalDate.parse(date); } catch (RuntimeException ignored) { return null; }
    }

    private boolean isRecentCandidate(GovPolicyItem item) {
        if (item.planningBaseline()) return true;
        LocalDate today = LocalDate.now(clock.withZone(POLICY_ZONE));
        LocalDate cutoff = today.minusDays(90);
        LocalDate published = publicationDay(item.publishedAt());
        if (published != null) return !published.isBefore(cutoff) && !published.isAfter(today);
        // URL dates can prove an archive is stale, but never become an invented publication date.
        if (item.url() != null) {
            try {
                String path = URI.create(item.url()).getPath();
                Matcher month = URL_MONTH_PATTERN.matcher(path == null ? "" : path);
                if (month.find()) {
                    LocalDate latestPossible = YearMonth.of(Integer.parseInt(month.group(1)), Integer.parseInt(month.group(2))).atEndOfMonth();
                    if (latestPossible.isBefore(cutoff)) return false;
                }
            } catch (RuntimeException ignored) { }
        }
        return true;
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

        // Keep a minimum of one item per usable source, then let reliable recency fill the pool.
        for (List<GovPolicyItem> sourceItems : bySource.values()) {
            sourceItems.sort(candidateOrder());
            if (!sourceItems.isEmpty()) selected.add(sourceItems.get(0));
            if (selected.size() >= limit) return selected;
        }

        if (selected.size() < limit) {
            List<GovPolicyItem> remaining = items.stream()
                    .filter(item -> !selected.contains(item))
                    .sorted(candidateOrder())
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

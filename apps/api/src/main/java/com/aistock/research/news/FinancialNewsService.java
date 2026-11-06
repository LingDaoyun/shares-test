package com.aistock.research.news;

import com.aistock.research.integration.news.FinancialNewsClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class FinancialNewsService {
    private static final Logger log = LoggerFactory.getLogger(FinancialNewsService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> CATEGORIES = List.of("MEDIA", "OFFICIAL");
    private static final int MAX_ITEMS = 2000;
    private static final int RESERVED_OFFICIAL_ITEMS = 500;
    private final FinancialNewsClient client;
    private final NewsAnalysisService analysis;
    private final FinancialNewsRepository repository;
    private final Clock clock;
    private final Executor executor;
    private final boolean enabled;
    private final Object analysisLock = new Object();
    private volatile FinancialNewsRepository.State state;

    @Autowired
    public FinancialNewsService(FinancialNewsClient client, NewsAnalysisService analysis, FinancialNewsRepository repository,
                                @Value("${research.news.enabled:true}") boolean enabled) {
        this(client, analysis, repository, Clock.systemUTC(), Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "financial-news-refresh");
            thread.setDaemon(true);
            return thread;
        }), enabled);
    }

    FinancialNewsService(FinancialNewsClient client, NewsAnalysisService analysis, FinancialNewsRepository repository,
                         Clock clock, Executor executor, boolean enabled) {
        this.client = client;
        this.analysis = analysis;
        this.repository = repository;
        this.clock = clock;
        this.executor = executor;
        this.enabled = enabled;
        state = repository.load().orElseGet(() -> new FinancialNewsRepository.State(FinancialNewsSnapshot.idle(), null, null));
        FinancialNewsSnapshot saved = state.snapshot();
        List<FinancialNewsItem> items = mergeItems(saved.items(), List.of(), clock.instant());
        Map<String, NewsAnalysis> analyses = retainedAnalyses(saved, items);
        if ("RUNNING".equals(saved.status())) {
            List<String> gaps = new ArrayList<>(saved.dataGaps());
            gaps.add("上次消息刷新因应用重启中断，已恢复最后保存的消息，可重新刷新。");
            persist(withSnapshot(new FinancialNewsSnapshot("FAILED", saved.startedAt(), saved.completedAt(),
                    items, saved.sources(), analyses, gaps)));
        } else if (!items.equals(saved.items()) || !analyses.equals(saved.analyses())) {
            persist(withSnapshot(new FinancialNewsSnapshot(saved.status(), saved.startedAt(), saved.completedAt(),
                    items, saved.sources(), analyses, saved.dataGaps())));
        }
    }

    /** HTTP polling only reads the saved state; it never fetches news or calls the model. */
    public FinancialNewsSnapshot current() { return state.snapshot(); }

    public synchronized FinancialNewsSnapshot refresh() { return start(CATEGORIES); }

    @Scheduled(initialDelayString = "${research.news.initial-delay-ms:15000}",
            fixedDelayString = "${research.news.check-delay-ms:60000}")
    public synchronized void checkSchedule() {
        if (!enabled || "RUNNING".equals(state.snapshot().status())) return;
        Instant now = clock.instant();
        List<String> due = new ArrayList<>();
        if (due(state.mediaLastAttemptAt(), Duration.ofMinutes(10), now)) due.add("MEDIA");
        if (due(state.officialLastAttemptAt(), Duration.ofMinutes(30), now)) due.add("OFFICIAL");
        if (!due.isEmpty()) start(due);
    }

    private boolean due(Instant previous, Duration interval, Instant now) {
        return previous == null || !now.isBefore(previous.plus(interval));
    }

    private FinancialNewsSnapshot start(List<String> categories) {
        FinancialNewsSnapshot previous = state.snapshot();
        if ("RUNNING".equals(previous.status())) return previous;
        Instant started = clock.instant();
        var running = new FinancialNewsSnapshot("RUNNING", started, previous.completedAt(), previous.items(),
                previous.sources(), previous.analyses(), previous.dataGaps());
        var next = new FinancialNewsRepository.State(running,
                categories.contains("MEDIA") ? started : state.mediaLastAttemptAt(),
                categories.contains("OFFICIAL") ? started : state.officialLastAttemptAt());
        try {
            persist(next);
            executor.execute(() -> collect(List.copyOf(categories), started));
        } catch (RuntimeException exception) {
            // Retain attempt times even when storage is down, avoiding a retry on every scheduler tick.
            state = next;
            fail("本次消息刷新未能启动或保存，已保留上次内容。");
        }
        return state.snapshot();
    }

    private void collect(List<String> categories, Instant attemptedAt) {
        List<FinancialNewsItem> incoming = new ArrayList<>();
        List<NewsSourceStatus> sourceUpdates = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (String category : categories) {
            try {
                FinancialNewsFetchResult result = client.fetch(category, attemptedAt);
                if (result == null) throw new IllegalStateException("消息采集未返回结果");
                incoming.addAll(result.items());
                sourceUpdates.addAll(result.sources());
            } catch (RuntimeException exception) {
                String label = "MEDIA".equals(category) ? "财经媒体" : "官方发布";
                gaps.add(label + "采集失败，已保留已有消息与上次成功时间。");
                state.snapshot().sources().stream().filter(source -> category.equals(source.category()))
                        .map(source -> new NewsSourceStatus(source.id(), source.name(), source.category(), source.url(),
                                "FAILED", 0, attemptedAt, null, "本次采集失败，已保留已有消息。"))
                        .forEach(sourceUpdates::add);
                log.warn("Financial news category refresh failed: {} ({})", category, exception.getClass().getSimpleName());
            }
        }
        synchronized (this) {
            try {
                // Analysis can complete while collection is running; merge with the current state, not its old copy.
                FinancialNewsSnapshot current = state.snapshot();
                List<FinancialNewsItem> items = mergeItems(current.items(), incoming, clock.instant());
                List<NewsSourceStatus> sources = mergeSources(current.sources(), sourceUpdates, attemptedAt);
                for (NewsSourceStatus source : sources) {
                    if (!"READY".equals(source.status())) gaps.add(source.name() + "：" + source.message());
                }
                boolean anySourceCompleted = sources.stream().anyMatch(source -> !"FAILED".equals(source.status()));
                String status = gaps.isEmpty() ? "READY" : items.isEmpty() && !anySourceCompleted ? "FAILED" : "PARTIAL";
                persist(withSnapshot(new FinancialNewsSnapshot(status, current.startedAt(), clock.instant(), items,
                        sources, retainedAnalyses(current, items), gaps.stream().distinct().toList())));
            } catch (RuntimeException exception) {
                fail("本次消息结果未能保存，已保留最后保存的内容和时间。");
            }
        }
    }

    private List<NewsSourceStatus> mergeSources(List<NewsSourceStatus> previous, List<NewsSourceStatus> incoming, Instant attemptedAt) {
        Map<String, NewsSourceStatus> merged = new LinkedHashMap<>();
        previous.forEach(source -> merged.put(source.id(), source));
        for (NewsSourceStatus source : incoming) {
            NewsSourceStatus old = merged.get(source.id());
            Instant success = "FAILED".equals(source.status()) ? old == null ? null : old.lastSuccessAt()
                    : source.lastSuccessAt() == null ? attemptedAt : source.lastSuccessAt();
            merged.put(source.id(), new NewsSourceStatus(source.id(), source.name(), source.category(), source.url(),
                    source.status(), source.itemCount(), attemptedAt, success, source.message()));
        }
        return List.copyOf(merged.values());
    }

    private List<FinancialNewsItem> mergeItems(List<FinancialNewsItem> previous, List<FinancialNewsItem> incoming, Instant now) {
        Map<String, FinancialNewsItem> urls = new LinkedHashMap<>();
        for (FinancialNewsItem item : previous) if (valid(item, now)) urls.put(canonicalUrl(item.url()), item);
        for (FinancialNewsItem item : incoming) {
            if (!valid(item, now)) continue;
            String url = canonicalUrl(item.url());
            FinancialNewsItem old = urls.get(url);
            String id = old == null ? item.id() : old.id();
            if (id == null || id.isBlank()) id = UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString();
            FinancialNewsItem next = new FinancialNewsItem(id, item.category(), item.title(), item.source(), item.url(), item.publishedAt(), item.summary());
            if (old == null || publicationTime(next.publishedAt()).compareTo(publicationTime(old.publishedAt())) >= 0) urls.put(url, next);
        }
        Set<String> titles = new HashSet<>();
        List<FinancialNewsItem> sorted = urls.values().stream().sorted(Comparator.comparing((FinancialNewsItem item) -> publicationTime(item.publishedAt())).reversed()
                        .thenComparing(FinancialNewsItem::id))
                .filter(item -> titles.add(item.source() + "\n" + Normalizer.normalize(item.title(), Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "")))
                .toList();
        // Lower-frequency official releases retain room even when newer media fills the snapshot.
        Set<FinancialNewsItem> selected = new HashSet<>(sorted.stream()
                .filter(item -> "OFFICIAL".equals(item.category())).limit(RESERVED_OFFICIAL_ITEMS).toList());
        for (FinancialNewsItem item : sorted) {
            if (selected.size() >= MAX_ITEMS) break;
            selected.add(item);
        }
        return sorted.stream().filter(selected::contains).toList();
    }

    private boolean valid(FinancialNewsItem item, Instant now) {
        if (item == null || !CATEGORIES.contains(item.category()) || item.title() == null || item.title().isBlank()
                || item.source() == null || item.source().isBlank() || canonicalUrl(item.url()) == null) return false;
        Instant published = publicationTime(item.publishedAt());
        if (published == null || published.isAfter(now)) return false;
        if (item.publishedAt().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return !published.atZone(SHANGHAI).toLocalDate().isBefore(now.atZone(SHANGHAI).toLocalDate().minusDays(30));
        }
        return !published.isBefore(now.minus(Duration.ofDays(30)));
    }

    /** Date-only values use day boundaries for ordering only; the original publishedAt string is preserved. */
    private Instant publicationTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (DateTimeException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); } catch (DateTimeException ignored) { }
        try { return LocalDateTime.parse(value.replace(' ', 'T')).atZone(SHANGHAI).toInstant(); } catch (DateTimeException ignored) { }
        try { return LocalDate.parse(value).atStartOfDay(SHANGHAI).toInstant(); } catch (DateTimeException ignored) { return null; }
    }

    private String canonicalUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim()).normalize();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            String query = uri.getRawQuery() == null ? "" : String.join("&", Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(part -> !part.toLowerCase(Locale.ROOT).matches("(?:utm_[^=]*|spm|from)=.*")).sorted().toList());
            String port = uri.getPort() < 0 || uri.getPort() == 80 || uri.getPort() == 443 ? "" : ":" + uri.getPort();
            return uri.getHost().toLowerCase(Locale.ROOT) + port + Objects.toString(uri.getRawPath(), "") + (query.isEmpty() ? "" : "?" + query);
        } catch (IllegalArgumentException exception) { return null; }
    }

    private Map<String, NewsAnalysis> retainedAnalyses(FinancialNewsSnapshot previous, List<FinancialNewsItem> items) {
        Map<String, FinancialNewsItem> oldItems = new HashMap<>();
        previous.items().forEach(item -> oldItems.put(item.id(), item));
        Map<String, NewsAnalysis> retained = new LinkedHashMap<>();
        for (FinancialNewsItem item : items) {
            NewsAnalysis saved = previous.analyses().get(item.id());
            FinancialNewsItem old = oldItems.get(item.id());
            if (saved != null && saved.analyzedAt() != null && item.url().equals(saved.evidenceUrl()) && old != null
                    && Objects.equals(old.title(), item.title()) && Objects.equals(old.summary(), item.summary())) retained.put(item.id(), saved);
        }
        return retained;
    }

    public NewsAnalysis analyze(String id) {
        // Serialize on-demand analyses without blocking collection or read-only polling.
        synchronized (analysisLock) {
            FinancialNewsSnapshot before = state.snapshot();
            FinancialNewsItem item = before.items().stream().filter(news -> news.id().equals(id)).findFirst().orElseThrow(NoSuchElementException::new);
            NewsAnalysis cached = before.analyses().get(id);
            if (cached != null && cached.analyzedAt() != null) return cached;
            NewsAnalysis result = analysis.analyze(item, clock.instant());
            if (result.analyzedAt() == null) return result;
            synchronized (this) {
                FinancialNewsSnapshot current = state.snapshot();
                if (current.items().stream().noneMatch(news -> news.equals(item))) {
                    return NewsAnalysisService.unavailable(item, "消息内容已更新或过期，本次解读未保存，请刷新消息后重试。");
                }
                Map<String, NewsAnalysis> analyses = new LinkedHashMap<>(current.analyses());
                analyses.put(id, result);
                try {
                    persist(withSnapshot(new FinancialNewsSnapshot(current.status(), current.startedAt(), current.completedAt(),
                            current.items(), current.sources(), analyses, current.dataGaps())));
                } catch (RuntimeException exception) {
                    return NewsAnalysisService.unavailable(item, "解读结果暂未保存，原有解读不受影响，可稍后重试。");
                }
            }
            return result;
        }
    }

    private synchronized void fail(String reason) {
        FinancialNewsSnapshot current = state.snapshot();
        List<String> gaps = new ArrayList<>(current.dataGaps());
        gaps.add(reason);
        var failed = withSnapshot(new FinancialNewsSnapshot("FAILED", current.startedAt(), current.completedAt(),
                current.items(), current.sources(), current.analyses(), gaps.stream().distinct().toList()));
        try { persist(failed); }
        catch (RuntimeException exception) {
            state = failed;
            log.warn("Financial news failure state could not be saved ({})", exception.getClass().getSimpleName());
        }
    }

    private FinancialNewsRepository.State withSnapshot(FinancialNewsSnapshot snapshot) {
        return new FinancialNewsRepository.State(snapshot, state.mediaLastAttemptAt(), state.officialLastAttemptAt());
    }

    private void persist(FinancialNewsRepository.State next) {
        repository.save(next);
        state = next;
    }

    @PreDestroy
    void shutdown() { if (executor instanceof ExecutorService service) service.shutdownNow(); }
}

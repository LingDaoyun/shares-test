package com.aistock.research.hotspot;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.news.IndustryNewsClient;
import com.aistock.research.integration.news.IndustryNewsItem;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class HotspotService {
    private static final Logger log = LoggerFactory.getLogger(HotspotService.class);
    private static final Duration MANUAL_COOLDOWN = Duration.ofMinutes(15);
    private static final Duration RETENTION = Duration.ofDays(7);
    private final IndustryNewsClient newsClient;
    private final HotspotAnalysisService analysis;
    private final HotspotCompanyClient companies;
    private final HotspotRepository repository;
    private final boolean enabled;
    private final Duration interval;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "industry-hotspot-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile HotspotRepository.State state;

    public HotspotService(IndustryNewsClient newsClient, HotspotAnalysisService analysis, HotspotCompanyClient companies,
                          HotspotRepository repository,
                          @Value("${research.hotspots.enabled:true}") boolean enabled,
                          @Value("${research.hotspots.interval-minutes:120}") int intervalMinutes) {
        this.newsClient = newsClient;
        this.analysis = analysis;
        this.companies = companies;
        this.repository = repository;
        this.enabled = enabled;
        this.interval = Duration.ofMinutes(Math.max(15, intervalMinutes));
        this.state = repository.load().orElseGet(() -> new HotspotRepository.State(HotspotSnapshot.idle(), null));
        if ("RUNNING".equals(state.snapshot().status())) {
            fail(state.snapshot(), "上次热点刷新因应用重启中断，已保留最后保存的内容。");
        }
    }

    /** Reading a snapshot never collects sources or spends model tokens. */
    public HotspotSnapshot current() { return state.snapshot(); }

    public synchronized HotspotSnapshot refresh() { return start(); }

    @Scheduled(initialDelayString = "${research.hotspots.initial-delay-ms:20000}",
            fixedDelayString = "${research.hotspots.check-delay-ms:60000}")
    public synchronized void checkSchedule() {
        if (!enabled || "RUNNING".equals(state.snapshot().status())) return;
        Instant next = state.snapshot().nextAutomaticRefreshAt();
        if (next == null || !Instant.now().isBefore(next)) start();
    }

    private HotspotSnapshot start() {
        HotspotSnapshot previous = state.snapshot();
        Instant now = Instant.now();
        if ("RUNNING".equals(previous.status()) || (previous.refreshAvailableAt() != null
                && now.isBefore(previous.refreshAvailableAt()))) return previous;
        HotspotSnapshot running = new HotspotSnapshot("RUNNING", previous.generatedAt(), now, now,
                enabled ? now.plus(interval) : null, now.plus(MANUAL_COOLDOWN), "正在采集产业新闻并核对关联公司",
                previous.newsCount(), previous.companyCount(), previous.news(), previous.events(), previous.dataGaps());
        try {
            persist(new HotspotRepository.State(running, state.analyzedNewsHash()));
            executor.execute(() -> runOnce(previous, running));
        } catch (RuntimeException exception) {
            state = new HotspotRepository.State(running, state.analyzedNewsHash());
            fail(previous, "本次任务启动或快照保存失败，未继续调用新闻与模型服务。");
        }
        return state.snapshot();
    }

    private void runOnce(HotspotSnapshot previous, HotspotSnapshot running) {
        try {
            Instant now = Instant.now();
            var collection = newsClient.fetchRecent(60);
            List<String> gaps = new ArrayList<>(collection.dataGaps());
            List<IndustryNewsItem> news = collection.items();
            if (news.isEmpty()) {
                fail(previous, "本次未取得有效近期新闻，保留上次内容及其原始时间；未调用模型。", gaps);
                return;
            }
            String newsHash = newsHash(news);
            List<HotspotEvent> events;
            String analyzedHash = state.analyzedNewsHash();
            String summary;
            if (newsHash.equals(analyzedHash)) {
                events = retained(previous.events(), now);
                summary = "新闻内容未变化，复用已保存研判并更新可取得的行情";
            } else {
                HotspotAnalysisService.Result result = analysis.analyze(news, now);
                gaps.addAll(result.dataGaps());
                events = merge(previous.events(), result.events(), now, result.analyzed());
                if (result.analyzed()) analyzedHash = newsHash;
                summary = result.analyzed() ? "已按新闻事件识别产业、产品与关联公司，保留近7天跟踪记录"
                        : "已采集新闻，部分产业或股票研判尚待完成";
            }
            if (events.size() > 40) {
                gaps.add("近7天跟踪超过40个事件，本快照仅保留最近更新的40个。");
                events = events.stream().limit(40).toList();
            }
            events = updatePrices(events, gaps);
            int companyCount = (int) events.stream().flatMap(event -> event.recommendations().stream())
                    .map(HotspotRecommendation::symbol).distinct().count();
            HotspotSnapshot result = new HotspotSnapshot(gaps.isEmpty() ? "READY" : "PARTIAL", Instant.now(),
                    running.startedAt(), running.lastAttemptAt(), running.nextAutomaticRefreshAt(), running.refreshAvailableAt(),
                    summary, news.size(), companyCount, List.copyOf(news), events, gaps.stream().distinct().toList());
            synchronized (this) { persist(new HotspotRepository.State(result, analyzedHash)); }
        } catch (RuntimeException exception) {
            log.warn("Industry hotspot refresh did not complete ({})", exception.getClass().getSimpleName());
            fail(previous, "新闻采集、研判或保存暂未完成，保留上次已保存内容。");
        }
    }

    private List<HotspotEvent> merge(List<HotspotEvent> previous, List<HotspotEvent> fresh, Instant now, boolean analyzed) {
        List<HotspotEvent> result = new ArrayList<>(retained(previous, now));
        for (HotspotEvent event : fresh) {
            int match = -1;
            for (int index = 0; index < result.size(); index++) {
                if (sameEvent(result.get(index), event)) { match = index; break; }
            }
            if (match < 0) { result.add(event); continue; }
            HotspotEvent old = result.get(match);
            // A temporary model outage should not replace a sourced analysis with a raw headline.
            if ("待研判".equals(event.eventType()) && !"待研判".equals(old.eventType())) continue;
            Map<String, IndustryNewsItem> sources = new LinkedHashMap<>();
            old.sources().forEach(source -> sources.put(source.id(), source));
            event.sources().forEach(source -> sources.put(source.id(), source));
            Map<String, HotspotRecommendation> oldStocks = old.recommendations().stream()
                    .collect(Collectors.toMap(HotspotRecommendation::symbol, stock -> stock, (left, right) -> left));
            // An incomplete review cannot revoke previously verified recommendations still within retention.
            List<HotspotRecommendation> reviewedStocks = !analyzed && event.recommendations().isEmpty()
                    ? old.recommendations() : event.recommendations();
            List<HotspotRecommendation> stocks = reviewedStocks.stream().map(stock -> {
                HotspotRecommendation first = oldStocks.get(stock.symbol());
                if (first == null) return stock;
                return copyPrice(stock, stock.latestPrice(), stock.changePercent(), stock.quoteUrl(), stock.quoteAsOf(),
                        first.firstSeenAt(), first.firstSeenPrice(), first.firstSeenQuoteAsOf());
            }).toList();
            result.set(match, new HotspotEvent(old.id(), event.title(), event.eventType(), event.industries(), event.products(),
                    event.summary(), event.catalyst(), event.heatScore(), event.confidence(),
                    recentSources(List.copyOf(sources.values()), now), stocks, event.risks(), old.firstSeenAt(), now));
        }
        return result.stream().sorted(Comparator.comparing(HotspotEvent::updatedAt).reversed()).toList();
    }

    private boolean sameEvent(HotspotEvent left, HotspotEvent right) {
        if (left.id().equals(right.id()) || left.sources().stream()
                .anyMatch(a -> right.sources().stream().anyMatch(b -> a.id().equals(b.id())))) return true;
        String products = productKey(left);
        return !products.isBlank() && products.equals(productKey(right)) && left.eventType().equals(right.eventType());
    }

    private String productKey(HotspotEvent event) {
        return event.products().stream().map(value -> value.replaceAll("[\\s\\p{Punct}]", "").toLowerCase())
                .filter(value -> value.length() >= 3).distinct().sorted().collect(Collectors.joining("|"));
    }

    private List<HotspotEvent> retained(List<HotspotEvent> events, Instant now) {
        List<HotspotEvent> result = new ArrayList<>();
        for (HotspotEvent event : events) {
            List<IndustryNewsItem> sources = recentSources(event.sources(), now);
            if (sources.isEmpty()) continue;
            // Recommendations expire with their cited news, independently from later stories in the event.
            List<HotspotRecommendation> stocks = event.recommendations().stream()
                    .filter(stock -> !recentSources(stock.evidence(), now).isEmpty()).toList();
            result.add(new HotspotEvent(event.id(), event.title(), event.eventType(), event.industries(), event.products(),
                    event.summary(), event.catalyst(), event.heatScore(), event.confidence(), sources, stocks, event.risks(),
                    event.firstSeenAt(), event.updatedAt()));
        }
        return result;
    }

    private List<IndustryNewsItem> recentSources(List<IndustryNewsItem> sources, Instant now) {
        return sources.stream().filter(item -> {
            try {
                Instant published = Instant.parse(item.publishedAt());
                return !published.isBefore(now.minus(RETENTION)) && !published.isAfter(now.plusSeconds(300));
            } catch (RuntimeException exception) { return false; }
        }).toList();
    }

    private List<HotspotEvent> updatePrices(List<HotspotEvent> events, List<String> gaps) {
        List<String> symbols = events.stream().flatMap(event -> event.recommendations().stream())
                .map(HotspotRecommendation::symbol).distinct().toList();
        Map<String, EastMoneyQuote> prices = companies.quoteIndex(symbols, gaps);
        return events.stream().map(event -> {
            List<HotspotRecommendation> stocks = event.recommendations().stream().map(stock -> {
                EastMoneyQuote quote = prices.get(stock.symbol());
                if (quote == null || quote.latestPrice() == null) return stock;
                return copyPrice(stock, quote.latestPrice(), quote.changePercent(), quote.quoteUrl(),
                        HotspotCompanyClient.quoteAsOf(quote), stock.firstSeenAt(), stock.firstSeenPrice(), stock.firstSeenQuoteAsOf());
            }).toList();
            return new HotspotEvent(event.id(), event.title(), event.eventType(), event.industries(), event.products(), event.summary(),
                    event.catalyst(), event.heatScore(), event.confidence(), event.sources(), stocks, event.risks(), event.firstSeenAt(), event.updatedAt());
        }).toList();
    }

    private HotspotRecommendation copyPrice(HotspotRecommendation stock, java.math.BigDecimal price,
            java.math.BigDecimal change, String url, String quoteAsOf, Instant firstSeenAt,
            java.math.BigDecimal firstSeenPrice, String firstSeenQuoteAsOf) {
        return new HotspotRecommendation(stock.symbol(), stock.name(), stock.industry(), stock.recommendationReason(), stock.benefitMechanism(),
                stock.relationType(), stock.evidence(), stock.companyEvidence(), stock.risks(), stock.confidence(), price, change,
                url, quoteAsOf, firstSeenAt, firstSeenPrice, firstSeenQuoteAsOf);
    }

    private String newsHash(List<IndustryNewsItem> news) {
        try {
            String body = analysis.modelIdentity() + "\n" + news.stream().sorted(Comparator.comparing(IndustryNewsItem::id))
                    .map(item -> item.id() + "\t" + item.title() + "\t" + item.publishedAt() + "\t" + item.excerpt())
                    .collect(Collectors.joining("\n"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void fail(HotspotSnapshot previous, String reason) {
        fail(previous, reason, List.of());
    }

    private synchronized void fail(HotspotSnapshot previous, String reason, List<String> currentGaps) {
        HotspotSnapshot attempt = state.snapshot();
        List<String> gaps = new ArrayList<>(previous.dataGaps());
        gaps.addAll(currentGaps);
        gaps.add(reason);
        HotspotSnapshot failed = new HotspotSnapshot("FAILED", previous.generatedAt(), attempt.startedAt(), attempt.lastAttemptAt(),
                attempt.nextAutomaticRefreshAt(), attempt.refreshAvailableAt(), reason, previous.newsCount(), previous.companyCount(),
                previous.news(), previous.events(), gaps.stream().distinct().toList());
        HotspotRepository.State next = new HotspotRepository.State(failed, state.analyzedNewsHash());
        try { persist(next); }
        catch (RuntimeException exception) {
            state = next;
            log.warn("Could not persist industry hotspot failure ({})", exception.getClass().getSimpleName());
        }
    }

    private void persist(HotspotRepository.State next) { repository.save(next); state = next; }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}

package com.aistock.research.policy;

import com.aistock.research.integration.gov.GovPolicyClient;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Once-per-day collection and bounded reviews, isolated from HTTP and intraday schedulers. */
@Service
public class PolicyDailyService {
    private static final Logger log = LoggerFactory.getLogger(PolicyDailyService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime RUN_AT = LocalTime.of(8, 30);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(SHANGHAI);

    private final PolicyThemeService themeService;
    private final PolicyAiReviewService reviewService;
    private final PolicyDailyRepository repository;
    private final Clock clock;
    private final Executor executor;
    private final int maxReviewThemes;
    private final boolean enabled;
    private volatile PolicyDailyState state;

    @Autowired
    public PolicyDailyService(
            PolicyThemeService themeService,
            PolicyAiReviewService reviewService,
            PolicyDailyRepository repository,
            @Value("${research.policy.daily.max-review-themes:8}") int maxReviewThemes,
            @Value("${research.policy.daily.enabled:true}") boolean enabled
    ) {
        this(themeService, reviewService, repository, Clock.systemUTC(), Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "policy-daily-review");
            thread.setDaemon(true);
            return thread;
        }), maxReviewThemes, enabled);
    }

    PolicyDailyService(PolicyThemeService themeService, PolicyAiReviewService reviewService,
                       PolicyDailyRepository repository, Clock clock, Executor executor, int maxReviewThemes) {
        this(themeService, reviewService, repository, clock, executor, maxReviewThemes, true);
    }

    private PolicyDailyService(PolicyThemeService themeService, PolicyAiReviewService reviewService,
                               PolicyDailyRepository repository, Clock clock, Executor executor,
                               int maxReviewThemes, boolean enabled) {
        this.themeService = themeService;
        this.reviewService = reviewService;
        this.repository = repository;
        this.clock = clock;
        this.executor = executor;
        this.maxReviewThemes = Math.max(0, maxReviewThemes);
        this.enabled = enabled;
        this.state = repository.load().orElseGet(() -> new PolicyDailyState(PolicyDailySnapshot.idle(), null, null));
        if ("RUNNING".equals(state.snapshot().status())) {
            PolicyDailySnapshot previous = state.snapshot();
            List<String> gaps = new ArrayList<>(previous.dataGaps());
            gaps.add("上次每日任务因应用重启中断（" + displayNow() + " 北京时间），可手动刷新重试。");
            persist(new PolicyDailyState(new PolicyDailySnapshot("FAILED", PolicyDailySnapshot.SCHEDULED_TIME,
                    previous.startedAt(), clock.instant(), previous.themes(), previous.reviewedThemeCodes(), gaps,
                    previous.unclassifiedSignals()),
                    state.lastAutomaticAttemptDate(), state.lastSuccessfulDate()));
        }
    }

    /** This accessor must never fetch policies, company data or invoke an LLM. */
    public PolicyDailySnapshot current() {
        return state.snapshot();
    }

    public synchronized PolicyDailySnapshot refresh() {
        return start(false);
    }

    /** Polling recovers naturally when a laptop wakes after 08:30; failed runs do not loop. */
    @Scheduled(initialDelayString = "${research.policy.daily.initial-delay-ms:10000}",
            fixedDelayString = "${research.policy.daily.check-delay-ms:60000}")
    public synchronized void checkSchedule() {
        if (!enabled || "RUNNING".equals(state.snapshot().status())) return;
        var now = clock.instant().atZone(SHANGHAI);
        LocalDate today = now.toLocalDate();
        if (now.toLocalTime().isBefore(RUN_AT)
                || today.equals(state.lastSuccessfulDate())
                || (state.lastAutomaticAttemptDate() != null && !today.isAfter(state.lastAutomaticAttemptDate()))) return;
        start(true);
    }

    private PolicyDailySnapshot start(boolean automatic) {
        if ("RUNNING".equals(state.snapshot().status())) return state.snapshot();
        PolicyDailySnapshot previous = state.snapshot();
        Instant startedAt = clock.instant();
        LocalDate runDate = startedAt.atZone(SHANGHAI).toLocalDate();
        List<String> runningGaps = new ArrayList<>(previous.dataGaps());
        if (!previous.themes().isEmpty() || !previous.unclassifiedSignals().isEmpty())
            runningGaps.add("每日采集与研判进行中，当前仍展示上次保存的主题、政策线索与研判覆盖。");
        PolicyDailySnapshot running = new PolicyDailySnapshot("RUNNING", PolicyDailySnapshot.SCHEDULED_TIME,
                startedAt, null, previous.themes(), previous.reviewedThemeCodes(), runningGaps, previous.unclassifiedSignals());
        PolicyDailyState next = new PolicyDailyState(running,
                automatic ? runDate : state.lastAutomaticAttemptDate(), state.lastSuccessfulDate());
        try {
            persist(next);
            executor.execute(() -> runOnce(previous, startedAt, runDate));
        } catch (RuntimeException exception) {
            // Keep the attempted date in memory even if storage is down; do not hammer it every minute.
            state = next;
            fail(previous, startedAt, "每日任务启动或持久化失败");
        }
        return state.snapshot();
    }

    private void runOnce(PolicyDailySnapshot previous, Instant startedAt, LocalDate runDate) {
        try {
            PolicyThemeCollection collection = themeService.listThemesWithStatus();
            List<PolicyTheme> collected = collection.themes();
            List<PolicySignal> unclassified = collection.unclassifiedSignals();
            boolean hasEvidence = collected.stream().anyMatch(this::hasEvidence) || unclassified.stream().anyMatch(this::realSignal);
            if (!hasEvidence && (previous.themes().stream().anyMatch(this::hasEvidence)
                    || previous.unclassifiedSignals().stream().anyMatch(this::realSignal))) {
                fail(previous, startedAt, "本次采集没有可核验的政策信号，已保留上次主题与政策线索");
                return;
            }
            List<String> gaps = new ArrayList<>(collection.dataGaps());
            List<String> reviewed = new ArrayList<>();
            List<PolicyTheme> eligible = collected.stream()
                    .filter(theme -> signals(theme).stream().anyMatch(signal -> eligibleSignal(signal, runDate)))
                    .toList();
            if (!hasEvidence) gaps.add("本次未采集到可核验的政策标题与原文链接，未调用 AI 研判。");
            int skipped = collected.size() - eligible.size();
            if (skipped > 0) gaps.add(skipped + " 个主题没有长期规划正文、近两年或日期待核验的有效信号，未进入每日研判。");
            if (eligible.size() > maxReviewThemes) gaps.add("每日 AI 预算最多 " + maxReviewThemes + " 个主题，另有 "
                    + (eligible.size() - maxReviewThemes) + " 个有证据主题未研判，可单独复核。");
            List<PolicyTheme> planned = prioritizeReviews(eligible, runDate, gaps).stream().limit(maxReviewThemes).toList();
            publishProgress(collected, unclassified, reviewed, gaps, startedAt, 0, planned.size());
            int processed = 0;
            for (PolicyTheme theme : planned) {
                if (signals(theme).stream().anyMatch(signal -> eligibleSignal(signal, runDate) && signalDate(signal) == null)) {
                    gaps.add(theme.name() + "：部分政策发布日期未知，时效需核验。");
                }
                try {
                    PolicyAiReview review = reviewService.review(theme);
                    if (review != null && review.reviewedAt() != null && review.trendPlan() != null) {
                        reviewed.add(theme.themeCode());
                    } else {
                        gaps.add(theme.name() + "：未完成有效的 AI 趋势研判。");
                    }
                    if (review != null) review.dataGaps().forEach(gap -> gaps.add(theme.name() + "：" + gap));
                } catch (RuntimeException exception) {
                    // Upstream exceptions can contain request details. Never put them into snapshots.
                    gaps.add(theme.name() + "：AI 研判失败，可稍后单独复核。");
                    log.warn("Daily policy review failed for {} ({})", theme.themeCode(), exception.getClass().getSimpleName());
                }
                publishProgress(collected, unclassified, reviewed, gaps, startedAt, ++processed, planned.size());
            }
            PolicyDailySnapshot result = new PolicyDailySnapshot(gaps.isEmpty() ? "READY" : "PARTIAL",
                    PolicyDailySnapshot.SCHEDULED_TIME, startedAt, clock.instant(), collected, reviewed, gaps, unclassified);
            synchronized (this) {
                persist(new PolicyDailyState(result, state.lastAutomaticAttemptDate(),
                        hasEvidence ? runDate : state.lastSuccessfulDate()));
            }
        } catch (RuntimeException exception) {
            log.warn("Daily policy collection or persistence failed ({})", exception.getClass().getSimpleName());
            fail(previous, startedAt, "每日采集或快照保存失败，已保留上次可用主题");
        }
    }

    private List<PolicyTheme> prioritizeReviews(List<PolicyTheme> eligible, LocalDate runDate, List<String> gaps) {
        Comparator<PolicyTheme> evidenceOrder = Comparator.comparing((PolicyTheme theme) ->
                        theme.strengthScore() == null ? BigDecimal.ZERO : theme.strengthScore())
                .reversed().thenComparing(theme -> newestDate(theme, runDate), Comparator.reverseOrder())
                .thenComparing(PolicyTheme::themeCode);
        Map<String, Instant> lastReviewed = new HashMap<>();
        for (PolicyTheme theme : eligible) {
            try {
                List<PolicyAiReview> history = reviewService.history(theme.themeCode());
                if (history == null) continue;
                history.stream().filter(Objects::nonNull)
                        .filter(review -> review.reviewedAt() != null && review.trendPlan() != null
                                && !review.reviewedAt().isAfter(clock.instant()))
                        .map(PolicyAiReview::reviewedAt).max(Comparator.naturalOrder())
                        .ifPresent(time -> lastReviewed.put(theme.themeCode(), time));
            } catch (RuntimeException exception) {
                // An unreadable history is not evidence that a theme has never been reviewed.
                gaps.add("部分成功研判历史读取失败，本轮按政策强度与日期排序；已保存历史仍保留。");
                log.warn("Daily policy history unavailable for {} ({})", theme.themeCode(), exception.getClass().getSimpleName());
                return eligible.stream().sorted(evidenceOrder).toList();
            }
        }
        return eligible.stream().sorted(Comparator.comparing((PolicyTheme theme) -> lastReviewed.get(theme.themeCode()),
                Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(evidenceOrder)).toList();
    }

    private synchronized void publishProgress(List<PolicyTheme> collected, List<PolicySignal> unclassified, List<String> reviewed,
                                              List<String> gaps, Instant startedAt, int processed, int planned) {
        List<String> progressGaps = new ArrayList<>(gaps);
        progressGaps.add("政策采集已完成；AI 研判进度 " + processed + "/" + planned
                + "，其中 " + reviewed.size() + " 个主题已完成有效研判。");
        PolicyDailySnapshot progress = new PolicyDailySnapshot("RUNNING", PolicyDailySnapshot.SCHEDULED_TIME,
                startedAt, null, collected, reviewed, progressGaps, unclassified);
        persist(new PolicyDailyState(progress, state.lastAutomaticAttemptDate(), state.lastSuccessfulDate()));
    }

    private synchronized void fail(PolicyDailySnapshot previous, Instant startedAt, String reason) {
        List<String> gaps = new ArrayList<>(previous.dataGaps());
        gaps.add(reason + "（" + displayNow() + " 北京时间）；可手动刷新重试。");
        PolicyDailyState failed = new PolicyDailyState(new PolicyDailySnapshot("FAILED", PolicyDailySnapshot.SCHEDULED_TIME,
                startedAt, clock.instant(), previous.themes(), previous.reviewedThemeCodes(), gaps, previous.unclassifiedSignals()),
                state.lastAutomaticAttemptDate(), state.lastSuccessfulDate());
        try {
            persist(failed);
        } catch (RuntimeException exception) {
            gaps.add("失败状态暂未写入数据库，应用重启后将恢复最后一次已保存快照。");
            state = new PolicyDailyState(new PolicyDailySnapshot("FAILED", PolicyDailySnapshot.SCHEDULED_TIME,
                    startedAt, clock.instant(), previous.themes(), previous.reviewedThemeCodes(), gaps, previous.unclassifiedSignals()),
                    state.lastAutomaticAttemptDate(), state.lastSuccessfulDate());
            log.warn("Could not persist daily policy failure ({})", exception.getClass().getSimpleName());
        }
    }

    private void persist(PolicyDailyState next) {
        repository.save(next);
        state = next;
    }

    private List<PolicySignal> signals(PolicyTheme theme) {
        return theme.signals() == null ? List.of() : theme.signals();
    }

    private boolean hasEvidence(PolicyTheme theme) {
        return signals(theme).stream().anyMatch(this::realSignal);
    }

    private boolean realSignal(PolicySignal signal) {
        if (signal == null || signal.summary() == null || signal.summary().isBlank() || signal.url() == null) return false;
        try {
            URI uri = URI.create(signal.url());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean eligibleSignal(PolicySignal signal, LocalDate today) {
        if (!realSignal(signal)) return false;
        LocalDate date = signalDate(signal);
        if (date != null && date.isAfter(today)) return false;
        if (signal.planningBaseline() && signal.excerpt() != null && !signal.excerpt().isBlank()) return true;
        return date == null || (!date.isBefore(today.minusDays(GovPolicyClient.RESEARCH_LOOKBACK_DAYS))
                && !date.isAfter(today));
    }

    private LocalDate newestDate(PolicyTheme theme, LocalDate today) {
        return signals(theme).stream().filter(signal -> eligibleSignal(signal, today))
                .map(this::signalDate).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(LocalDate.MIN);
    }

    private LocalDate signalDate(PolicySignal signal) {
        if (signal.publishedAt() == null || signal.publishedAt().isBlank()) return null;
        String value = signal.publishedAt().trim();
        try {
            return OffsetDateTime.parse(value.replace(' ', 'T')).atZoneSameInstant(SHANGHAI).toLocalDate();
        } catch (DateTimeParseException ignored) { }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T')).atZone(SHANGHAI).toLocalDate();
        } catch (DateTimeParseException ignored) { }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) { return null; }
    }

    private String displayNow() { return DISPLAY_TIME.format(clock.instant()); }

    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }
}

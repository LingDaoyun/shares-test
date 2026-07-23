package com.aistock.research.shortterm.schedule;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.OvernightRuleSet;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScanRequest;
import com.aistock.research.shortterm.ShortTermService;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.FINAL;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.PRESELECT;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.READINESS_GUARD;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.DATA_BLOCKED;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FAILED;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FINAL_READY;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.NO_TRADE;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.PRESELECT_READY;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.RUNNING;

@Service
public class ShortTermScheduledScanService {

    private static final Logger log = LoggerFactory.getLogger(ShortTermScheduledScanService.class);
    private static final BigDecimal MINIMUM_COVERAGE = new BigDecimal("0.90");
    private static final Duration DEFAULT_STALE_RUN_TIMEOUT = Duration.ofMinutes(10);
    private static final String EXECUTOR_SATURATED = "SCHEDULED_EXECUTOR_SATURATED";

    private final ShortTermAutomationSettings settings;
    private final TradingClockService tradingClock;
    private final ShortTermScheduledSnapshotStore store;
    private final ShortTermService shortTermService;
    private final ResearchHistoryService historyService;
    private final RecommendationAttestationService attestationService;
    private final ObjectMapper canonicalObjectMapper;
    private final ExecutorService executor;
    private final Clock clock;
    private final Duration staleRunTimeout;

    @Autowired
    public ShortTermScheduledScanService(
            ShortTermAutomationSettings settings,
            TradingClockService tradingClock,
            ShortTermScheduledSnapshotStore store,
            ShortTermService shortTermService,
            ResearchHistoryService historyService,
            RecommendationAttestationService attestationService,
            ObjectMapper objectMapper,
            @Qualifier("shortTermScheduledExecutor") ExecutorService executor
    ) {
        this(
                settings, tradingClock, store, shortTermService, historyService, attestationService,
                objectMapper, executor, Clock.system(TradingClockService.CHINA_MARKET_ZONE),
                DEFAULT_STALE_RUN_TIMEOUT);
    }

    ShortTermScheduledScanService(
            ShortTermAutomationSettings settings,
            TradingClockService tradingClock,
            ShortTermScheduledSnapshotStore store,
            ShortTermService shortTermService,
            ResearchHistoryService historyService,
            RecommendationAttestationService attestationService,
            ObjectMapper objectMapper,
            ExecutorService executor,
            Clock clock,
            Duration staleRunTimeout
    ) {
        this.settings = settings;
        this.tradingClock = tradingClock;
        this.store = store;
        this.shortTermService = shortTermService;
        this.historyService = historyService;
        this.attestationService = attestationService;
        this.canonicalObjectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.executor = executor;
        this.clock = clock.withZone(TradingClockService.CHINA_MARKET_ZONE);
        this.staleRunTimeout = requirePositive(staleRunTimeout);
    }

    public void submit(ShortTermSnapshotStage stage) {
        Optional<PreparedRun> prepared = prepare(stage);
        if (prepared.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> execute(prepared.orElseThrow()));
        } catch (RejectedExecutionException exception) {
            PreparedRun rejected = prepared.orElseThrow();
            publishBlocked(
                    rejected.claim(), null, null, "短线定时执行器队列已满",
                    List.of(EXECUTOR_SATURATED), rejected.startedAt());
            log.warn("Scheduled short-term queue saturated, runKey={}, stage={}",
                    rejected.claim().snapshotKey(), rejected.stage());
        }
    }

    public void runNow(ShortTermSnapshotStage stage) {
        prepare(stage).ifPresent(this::execute);
    }

    ResolvedParameters resolvedParameters() {
        return resolveParameters(settings.scanRequest(), settings.overnightRules());
    }

    private Optional<PreparedRun> prepare(ShortTermSnapshotStage stage) {
        requireScheduledStage(stage);
        LocalDate tradeDate = tradingClock.currentMarketDate();
        if (!settings.enabled() || tradingClock.isMarketClosedDay(tradeDate)) {
            return Optional.empty();
        }
        Instant startedAt = clock.instant();
        ResolvedParameters parameters = resolvedParameters();
        Optional<ShortTermSnapshotClaim> claim = store.claim(
                tradeDate, stage, parameters.fingerprint(), parameters.json(), startedAt);
        if (claim.isPresent()) {
            return Optional.of(new PreparedRun(tradeDate, stage, parameters, claim.orElseThrow(), startedAt));
        }
        Optional<ShortTermScheduledSnapshot> existing =
                store.find(tradeDate, stage, parameters.fingerprint());
        if (existing.isEmpty() || existing.orElseThrow().status() != RUNNING) {
            return Optional.empty();
        }
        ShortTermScheduledSnapshot running = existing.orElseThrow();
        Instant staleCutoff = startedAt.minus(staleRunTimeout);
        if (running.startedAt() == null || running.startedAt().isAfter(staleCutoff)) {
            return Optional.empty();
        }
        return store.recoverStaleRunning(
                        tradeDate, stage, parameters.fingerprint(), running.attemptCount(),
                        staleCutoff, startedAt)
                .map(recovered -> new PreparedRun(
                        tradeDate, stage, parameters, recovered, startedAt));
    }

    private void execute(PreparedRun run) {
        Instant began = clock.instant();
        try {
            switch (run.stage()) {
                case PRESELECT -> runPreselect(run);
                case FINAL -> runFinal(run);
                case READINESS_GUARD -> runReadinessGuard(run);
                default -> throw new IllegalArgumentException(
                        "Unsupported scheduled stage " + run.stage());
            }
        } catch (RuntimeException exception) {
            failClaim(run, exception);
        } finally {
            log.info("Scheduled short-term run completed, runKey={}, stage={}, durationMs={}",
                    run.claim().snapshotKey(), run.stage(),
                    Duration.between(began, clock.instant()).toMillis());
        }
    }

    private void runPreselect(PreparedRun run) {
        ShortTermReport report;
        try {
            report = shortTermService.report(run.parameters().scanRequest());
        } catch (RuntimeException exception) {
            Instant blockedAt = clock.instant();
            publishBlocked(
                    run.claim(), null, null, "预选证据获取失败：" + rootMessage(exception),
                    List.of("EVIDENCE_UNAVAILABLE"), blockedAt);
            return;
        }
        Instant completedAt = clock.instant();
        store.finish(
                run.claim(), PRESELECT_READY, report,
                report == null ? null : report.dataCutoffAt(),
                completedAt, "盘中预选已就绪", List.of());
    }

    private void runFinal(PreparedRun run) {
        Instant decisionAt = clock.instant();
        if (afterFinalDeadline(run.tradeDate(), decisionAt)) {
            publishBlocked(
                    run.claim(), null, null, "尾盘终选已超过完成截止时间",
                    List.of("FINAL_DEADLINE_EXPIRED"), decisionAt);
            return;
        }
        Optional<ShortTermScheduledSnapshot> preselect = store.find(
                run.tradeDate(), PRESELECT, run.parameters().fingerprint());
        if (preselect.isEmpty() || preselect.orElseThrow().status() != PRESELECT_READY
                || preselect.orElseThrow().report() == null) {
            publishBlocked(
                    run.claim(), null, null, "缺少当日有效预选结果",
                    List.of("PRESELECT_MISSING"), decisionAt);
            return;
        }
        Set<String> reviewedSymbols = Set.copyOf(preselect.orElseThrow().report().reviewedSymbols());
        if (reviewedSymbols.isEmpty()) {
            publishBlocked(
                    run.claim(), null, null, "当日预选复核股票为空",
                    List.of("PRESELECT_EMPTY"), decisionAt);
            return;
        }

        ShortTermReport report;
        try {
            report = shortTermService.finalReport(
                    run.parameters().scanRequest(), reviewedSymbols);
        } catch (RuntimeException exception) {
            Instant blockedAt = clock.instant();
            publishBlocked(
                    run.claim(), null, null, "尾盘证据获取失败：" + rootMessage(exception),
                    List.of("EVIDENCE_UNAVAILABLE"), blockedAt);
            return;
        }
        Instant completedAt = clock.instant();
        Optional<GateFailure> failure = validateFinal(
                run.tradeDate(), report, completedAt, completedAt);
        if (failure.isPresent()) {
            GateFailure blocked = failure.orElseThrow();
            publishBlocked(
                    run.claim(), report, report == null ? null : report.dataCutoffAt(),
                    blocked.message(), List.of(blocked.reason()), completedAt);
            return;
        }
        if (report.candidates().isEmpty()) {
            store.finish(
                    run.claim(), NO_TRADE, report, report.dataCutoffAt(), completedAt,
                    "全部执行闸门通过，今日无合格候选", List.of());
            return;
        }

        ShortTermReport attested = attestationService.attest(report);
        historyService.recordShortTermReport(attested);
        store.finish(
                run.claim(), FINAL_READY, attested, report.dataCutoffAt(), completedAt,
                "尾盘最终结果已就绪", List.of());
    }

    private void runReadinessGuard(PreparedRun run) {
        Instant checkedAt = clock.instant();
        Optional<ShortTermScheduledSnapshot> finalSnapshot = store.find(
                run.tradeDate(), FINAL, run.parameters().fingerprint());
        if (finalSnapshot.isEmpty()) {
            publishBlocked(
                    run.claim(), null, null, "尾盘最终快照缺失",
                    List.of("FINAL_MISSING"), checkedAt);
            return;
        }
        ShortTermScheduledSnapshot snapshot = finalSnapshot.orElseThrow();
        if (snapshot.status() == FAILED || snapshot.status() == DATA_BLOCKED) {
            publishBlocked(
                    run.claim(), null, null, "尾盘最终快照执行失败",
                    List.of("FINAL_FAILED"), checkedAt);
            return;
        }
        if (snapshot.status() != FINAL_READY && snapshot.status() != NO_TRADE) {
            publishBlocked(
                    run.claim(), null, null, "尾盘最终快照已经过期",
                    List.of("FINAL_STALE"), checkedAt);
            return;
        }
        if (snapshot.completedAt() == null) {
            publishBlocked(
                    run.claim(), null, null, "尾盘最终快照已经过期",
                    List.of("FINAL_STALE"), checkedAt);
            return;
        }
        Optional<GateFailure> failure = validateFinal(
                run.tradeDate(), snapshot.report(), snapshot.completedAt(), checkedAt);
        if (failure.isPresent()) {
            publishBlocked(
                    run.claim(), null, null, "尾盘最终快照已经过期",
                    List.of("FINAL_STALE"), checkedAt);
            return;
        }
        store.finish(
                run.claim(), snapshot.status(), snapshot.report(), snapshot.dataCutoffAt(), checkedAt,
                "尾盘最终快照通过就绪检查", List.of());
    }

    private Optional<GateFailure> validateFinal(
            LocalDate tradeDate,
            ShortTermReport report,
            Instant decisionCompletedAt,
            Instant freshnessCheckedAt
    ) {
        if (afterFinalDeadline(tradeDate, decisionCompletedAt)) {
            return Optional.of(new GateFailure(
                    "FINAL_DEADLINE_EXPIRED", "尾盘终选已超过完成截止时间"));
        }
        if (report == null) {
            return Optional.of(new GateFailure("FINAL_REPORT_MISSING", "尾盘终选报告缺失"));
        }
        ShortTermCoverageSnapshot coverage = report.coverage();
        if (coverage == null || !coverage.executionReliable()
                || coverage.coverageRatio() == null
                || coverage.coverageRatio().compareTo(MINIMUM_COVERAGE) < 0) {
            return Optional.of(new GateFailure("COVERAGE_BELOW_90", "全市场行情覆盖率低于90%"));
        }
        Instant cutoff = report.dataCutoffAt();
        if (cutoff == null) {
            return Optional.of(new GateFailure("CUTOFF_MISSING", "尾盘行情截止时间缺失"));
        }
        ZoneId zone = resolvedZone();
        if (!LocalDateTime.ofInstant(cutoff, zone).toLocalDate().equals(tradeDate)) {
            return Optional.of(new GateFailure("CUTOFF_WRONG_DATE", "尾盘行情不是当日数据"));
        }
        if (cutoff.isAfter(decisionCompletedAt)) {
            return Optional.of(new GateFailure("CUTOFF_AFTER_DECISION", "尾盘行情时间晚于决策时刻"));
        }
        if (Duration.between(cutoff, freshnessCheckedAt).compareTo(settings.freshness()) > 0) {
            return Optional.of(new GateFailure("QUOTE_STALE", "尾盘行情已经过期"));
        }
        return Optional.empty();
    }

    private boolean afterFinalDeadline(LocalDate tradeDate, Instant instant) {
        LocalDateTime marketTime = LocalDateTime.ofInstant(instant, resolvedZone());
        return !marketTime.toLocalDate().equals(tradeDate)
                || marketTime.toLocalTime().isAfter(settings.finalDeadline());
    }

    private ZoneId resolvedZone() {
        return ZoneId.of(settings.zone());
    }

    private void publishBlocked(
            ShortTermSnapshotClaim claim,
            ShortTermReport report,
            Instant dataCutoffAt,
            String message,
            List<String> reasons,
            Instant completedAt
    ) {
        store.finish(
                claim, DATA_BLOCKED, report, dataCutoffAt, completedAt, message, reasons);
    }

    private void failClaim(PreparedRun run, RuntimeException exception) {
        String message = rootMessage(exception);
        try {
            store.fail(run.claim(), clock.instant(), message, List.of(message));
        } catch (RuntimeException publicationFailure) {
            log.error("Unable to persist scheduled failure, runKey={}, stage={}, reason={}",
                    run.claim().snapshotKey(), run.stage(), rootMessage(publicationFailure));
        }
        log.warn("Scheduled short-term run failed, runKey={}, stage={}, reason={}",
                run.claim().snapshotKey(), run.stage(), message);
    }

    private ResolvedParameters resolveParameters(
            ShortTermScanRequest scanRequest,
            OvernightRuleSet overnightRules
    ) {
        try {
            ParameterDocument document = new ParameterDocument(scanRequest, overnightRules);
            String json = canonicalObjectMapper.writeValueAsString(document);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String fingerprint = HexFormat.of().formatHex(
                    digest.digest(json.getBytes(StandardCharsets.UTF_8)));
            return new ResolvedParameters(scanRequest, overnightRules, json, fingerprint);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to resolve scheduled scan parameters", exception);
        }
    }

    private void requireScheduledStage(ShortTermSnapshotStage stage) {
        if (stage != PRESELECT && stage != FINAL && stage != READINESS_GUARD) {
            throw new IllegalArgumentException("Unsupported scheduled stage " + stage);
        }
    }

    private Duration requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("staleRunTimeout must be positive");
        }
        return duration;
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    record ResolvedParameters(
            ShortTermScanRequest scanRequest,
            OvernightRuleSet overnightRules,
            String json,
            String fingerprint
    ) {
    }

    private record ParameterDocument(
            ShortTermScanRequest scanRequest,
            OvernightRuleSet overnightRules
    ) {
    }

    private record PreparedRun(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            ResolvedParameters parameters,
            ShortTermSnapshotClaim claim,
            Instant startedAt
    ) {
    }

    private record GateFailure(String reason, String message) {
    }
}

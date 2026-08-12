package com.aistock.research.shortterm.schedule;

import com.aistock.research.history.ResearchHistoryService;
import com.aistock.research.shortterm.OvernightRuleSet;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private final ShortTermFinalResultGate finalResultGate;

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
        this.finalResultGate = new ShortTermFinalResultGate(settings, tradingClock);
    }

    public void submit(ShortTermSnapshotStage stage) {
        Optional<PreparedRun> prepared = prepare(stage);
        if (prepared.isEmpty()) {
            return;
        }
        enqueue(List.of(prepared.orElseThrow()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverCurrentDayAfterStartup() {
        LocalDate tradeDate = tradingClock.currentMarketDate();
        if (tradeDate == null) {
            log.warn("Skipping scheduled short-term recovery because the market date is unavailable");
            return;
        }
        Instant publicationDeadline = finalPublicationDeadline(tradeDate);
        for (ShortTermScheduledSnapshot finalReady : store.finalReady(tradeDate)) {
            if (!finalReady.hasCertifiedPublicationProof(publicationDeadline)) {
                store.expireUncertifiedFinal(finalReady, publicationDeadline);
            }
        }
        Optional<ShortTermScheduledSnapshot> latestFinal = store.latest(tradeDate, FINAL);
        latestFinal.map(snapshot -> failClosedIfUncertified(snapshot, tradeDate))
                .filter(snapshot -> snapshot.status() == FINAL_READY)
                .ifPresent(this::archiveFinalHistory);
        store.pendingFinals(tradeDate).forEach(store::expirePendingFinal);

        Instant restartedAt = clock.instant();
        boolean finalDeadlineExpired = afterFinalDeadline(tradeDate, restartedAt);
        if (finalDeadlineExpired) {
            store.running(tradeDate, FINAL).forEach(store::expireRunningFinal);
        }
        if (!settings.enabled() || tradingClock.isMarketClosedDay(tradeDate)
                || finalDeadlineExpired) {
            return;
        }

        Instant staleCutoff = restartedAt.minus(staleRunTimeout);
        List<PreparedRun> recoveredRuns = new ArrayList<>(2);
        for (ShortTermScheduledSnapshot running : store.running(tradeDate, PRESELECT)) {
            recoverStartupStage(
                    tradeDate, PRESELECT, running,
                    staleCutoff, restartedAt).ifPresent(recoveredRuns::add);
        }
        for (ShortTermScheduledSnapshot running : store.running(tradeDate, FINAL)) {
            recoverStartupStage(
                    tradeDate, FINAL, running,
                    staleCutoff, restartedAt).ifPresent(recoveredRuns::add);
        }
        enqueue(recoveredRuns);
    }

    private void enqueue(List<PreparedRun> runs) {
        if (runs.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> runs.forEach(this::execute));
        } catch (RejectedExecutionException exception) {
            for (PreparedRun rejected : runs) {
                publishBlocked(
                        rejected.claim(), null, null, "短线定时执行器队列已满",
                        List.of(EXECUTOR_SATURATED), rejected.startedAt());
                log.warn("Scheduled short-term queue saturated, runKey={}, stage={}",
                        rejected.claim().snapshotKey(), rejected.stage());
            }
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

    private Optional<PreparedRun> recoverStartupStage(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            ShortTermScheduledSnapshot running,
            Instant staleCutoff,
            Instant restartedAt
    ) {
        if (running.status() != RUNNING || running.startedAt() == null
                || running.startedAt().isAfter(staleCutoff)) {
            return Optional.empty();
        }
        Optional<ShortTermSnapshotClaim> recovered = store.recoverStaleRunning(
                tradeDate, stage, running.parameterFingerprint(), running.attemptCount(),
                staleCutoff, restartedAt);
        if (recovered.isEmpty()) {
            return Optional.empty();
        }
        try {
            ResolvedParameters parameters = persistedParameters(running);
            return Optional.of(new PreparedRun(
                    tradeDate, stage, parameters, recovered.orElseThrow(), restartedAt));
        } catch (RuntimeException exception) {
            ShortTermSnapshotClaim claim = recovered.orElseThrow();
            store.fail(
                    claim, restartedAt, "持久化调度参数无效：" + rootMessage(exception),
                    List.of("PERSISTED_PARAMETERS_INVALID"));
            return Optional.empty();
        }
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
        ShortTermFinalResultGate.Result result = finalResultGate.evaluateScheduled(
                run.tradeDate(), report, completedAt, completedAt);
        if (result.status() == DATA_BLOCKED) {
            publishBlocked(
                    run.claim(), report, report == null ? null : report.dataCutoffAt(),
                    result.message(), result.blockedReasons(), completedAt);
            return;
        }
        if (result.status() == NO_TRADE) {
            store.finish(
                    run.claim(), NO_TRADE, report, report.dataCutoffAt(), completedAt,
                    result.message(), List.of());
            return;
        }

        ShortTermScheduledSnapshot published = store.finishFinalBeforeDeadline(
                run.claim(), report, report.dataCutoffAt(),
                finalPublicationDeadline(run.tradeDate()), clock, result.message());
        published = failClosedIfUncertified(published, run.tradeDate());
        if (published.status() == FINAL_READY) {
            archiveFinalHistory(published);
        }
    }

    private void archiveFinalHistory(ShortTermScheduledSnapshot snapshot) {
        String snapshotIdentity = snapshot.snapshotKey();
        ShortTermReport report = snapshot.report();
        if (!snapshot.hasCertifiedPublicationProof(finalPublicationDeadline(snapshot.tradeDate()))) {
            throw new IllegalStateException("Cannot archive an uncertified scheduled final");
        }
        try {
            ShortTermReport attested = attestationService.attest(report);
            if (attested == null) {
                throw new IllegalStateException("recommendation attestation returned no report");
            }
            historyService.recordShortTermReport(snapshotIdentity, attested);
        } catch (RuntimeException exception) {
            log.warn(
                    "FINAL_READY retained; history archival remains retryable, snapshotKey={}, reason={}",
                    snapshotIdentity, rootMessage(exception), exception);
        }
    }

    private ShortTermScheduledSnapshot failClosedIfUncertified(
            ShortTermScheduledSnapshot snapshot,
            LocalDate tradeDate
    ) {
        if (snapshot.status() != FINAL_READY) {
            return snapshot;
        }
        Instant publicationDeadline = finalPublicationDeadline(tradeDate);
        if (snapshot.hasCertifiedPublicationProof(publicationDeadline)) {
            return snapshot;
        }
        return store.expireUncertifiedFinal(snapshot, publicationDeadline);
    }

    private void runReadinessGuard(PreparedRun run) {
        Instant checkedAt = clock.instant();
        store.pendingFinals(run.tradeDate()).forEach(store::expirePendingFinal);
        store.running(run.tradeDate(), FINAL).forEach(store::expireRunningFinal);
        Optional<ShortTermScheduledSnapshot> finalSnapshot = store.latest(run.tradeDate(), FINAL);
        if (finalSnapshot.isEmpty()) {
            publishBlocked(
                    run.claim(), null, null, "尾盘最终快照缺失",
                    List.of("FINAL_MISSING"), checkedAt);
            return;
        }
        ShortTermScheduledSnapshot snapshot = failClosedIfUncertified(
                finalSnapshot.orElseThrow(), run.tradeDate());
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
        ShortTermFinalResultGate.Result result = finalResultGate.evaluateScheduled(
                run.tradeDate(), snapshot.report(), snapshot.completedAt(), checkedAt);
        if (result.status() == DATA_BLOCKED) {
            publishBlocked(
                    run.claim(), null, null, "尾盘最终快照已经过期",
                    List.of("FINAL_STALE"), checkedAt);
            return;
        }
        store.finish(
                run.claim(), snapshot.status(), snapshot.report(), snapshot.dataCutoffAt(), checkedAt,
                "14:49:40 前买入确认已通过就绪检查", List.of());
    }

    private boolean afterFinalDeadline(LocalDate tradeDate, Instant instant) {
        LocalDateTime marketTime = LocalDateTime.ofInstant(instant, resolvedZone());
        return !marketTime.toLocalDate().equals(tradeDate)
                || marketTime.toLocalTime().isAfter(settings.finalDeadline());
    }

    private Instant finalPublicationDeadline(LocalDate tradeDate) {
        return tradeDate.atTime(settings.finalDeadline()).atZone(resolvedZone()).toInstant();
    }

    private ZoneId resolvedZone() {
        settings.zone();
        return TradingClockService.CHINA_MARKET_ZONE;
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

    private ResolvedParameters persistedParameters(ShortTermScheduledSnapshot snapshot) {
        if (snapshot.parametersJson() == null || snapshot.parametersJson().isBlank()) {
            throw new IllegalStateException("parametersJson is missing");
        }
        try {
            ParameterDocument document = canonicalObjectMapper.readValue(
                    snapshot.parametersJson(), ParameterDocument.class);
            ResolvedParameters resolved = resolveParameters(
                    document.scanRequest(), document.overnightRules());
            if (!resolved.fingerprint().equals(snapshot.parameterFingerprint())) {
                throw new IllegalStateException("parameter fingerprint does not match persisted JSON");
            }
            return resolved;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read persisted scheduled scan parameters", exception);
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

}

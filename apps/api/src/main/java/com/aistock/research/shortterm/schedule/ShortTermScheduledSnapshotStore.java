package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class ShortTermScheduledSnapshotStore {

    private static final TypeReference<List<String>> BLOCKED_REASONS_TYPE = new TypeReference<>() { };
    private static final String RUNNING_MESSAGE = "正在执行";
    private static final int PARAMETER_FINGERPRINT_MAX_LENGTH = 64;
    private static final int SNAPSHOT_KEY_MAX_LENGTH = 160;
    private static final Set<ShortTermSnapshotStatus> FINISH_STATUSES = Set.of(
            ShortTermSnapshotStatus.PRESELECT_READY,
            ShortTermSnapshotStatus.FINAL_READY,
            ShortTermSnapshotStatus.NO_TRADE,
            ShortTermSnapshotStatus.DATA_BLOCKED
    );
    private static final Set<ShortTermSnapshotStatus> FINAL_TERMINAL_STATUSES = Set.of(
            ShortTermSnapshotStatus.FINAL_READY,
            ShortTermSnapshotStatus.NO_TRADE,
            ShortTermSnapshotStatus.DATA_BLOCKED,
            ShortTermSnapshotStatus.FAILED
    );

    private final ShortTermScheduledSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate finalPublicationTransaction;
    private final Supplier<Instant> databaseTime;

    @Autowired
    public ShortTermScheduledSnapshotStore(
            ShortTermScheduledSnapshotRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(repository, objectMapper, transactionManager,
                () -> repository.currentDatabaseTime().toInstant());
    }

    private ShortTermScheduledSnapshotStore(
            ShortTermScheduledSnapshotRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> databaseTime
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.databaseTime = databaseTime;
        this.finalPublicationTransaction = new TransactionTemplate(transactionManager);
        this.finalPublicationTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    static ShortTermScheduledSnapshotStore withDatabaseTimeForTest(
            ShortTermScheduledSnapshotRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> databaseTime
    ) {
        return new ShortTermScheduledSnapshotStore(
                repository, objectMapper, transactionManager, databaseTime);
    }

    public Optional<ShortTermSnapshotClaim> claim(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            String parametersJson,
            Instant startedAt
    ) {
        String snapshotKey = validatedClaimSnapshotKey(
                tradeDate, stage, parameterFingerprint, parametersJson, startedAt);
        try {
            ShortTermScheduledSnapshotEntity entity = repository.saveAndFlush(new ShortTermScheduledSnapshotEntity(
                    snapshotKey, tradeDate, stage, parameterFingerprint, parametersJson, startedAt));
            return Optional.of(new ShortTermSnapshotClaim(snapshotKey, entity.getAttemptCount()));
        } catch (DataIntegrityViolationException exception) {
            if (!repository.existsById(snapshotKey)) {
                throw exception;
            }
            int reclaimed = repository.reclaimFailed(
                    snapshotKey, ShortTermSnapshotStatus.RUNNING, ShortTermSnapshotStatus.FAILED,
                    startedAt, RUNNING_MESSAGE);
            if (reclaimed != 1) {
                return Optional.empty();
            }
            return Optional.of(currentClaim(snapshotKey));
        }
    }

    @Transactional
    public Optional<ShortTermSnapshotClaim> recoverStaleRunning(
            ShortTermSnapshotClaim staleClaim,
            Instant staleCutoff,
            Instant restartedAt
    ) {
        int reclaimed = repository.reclaimStaleRunning(
                staleClaim.snapshotKey(), staleClaim.attemptCount(), ShortTermSnapshotStatus.RUNNING,
                staleCutoff, restartedAt, RUNNING_MESSAGE);
        if (reclaimed != 1) {
            return Optional.empty();
        }
        return Optional.of(currentClaim(staleClaim.snapshotKey()));
    }

    @Transactional
    public Optional<ShortTermSnapshotClaim> recoverStaleRunning(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            int expectedAttemptCount,
            Instant staleCutoff,
            Instant restartedAt
    ) {
        String snapshotKey = validatedSnapshotKey(tradeDate, stage, parameterFingerprint);
        return recoverStaleRunning(
                new ShortTermSnapshotClaim(snapshotKey, expectedAttemptCount), staleCutoff, restartedAt);
    }

    @Transactional
    public ShortTermScheduledSnapshot finish(
            ShortTermSnapshotClaim claim,
            ShortTermSnapshotStatus status,
            ShortTermReport report,
            Instant dataCutoffAt,
            Instant completedAt,
            String message,
            List<String> blockedReasons
    ) {
        if (!FINISH_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Finish status must be a publishable terminal status");
        }
        if (status == ShortTermSnapshotStatus.FINAL_READY
                && claimedStage(claim) == ShortTermSnapshotStage.FINAL) {
            throw new IllegalArgumentException(
                    "FINAL_READY must be published through deadline certification");
        }
        return publishTerminal(
                claim, status, writeReport(report), dataCutoffAt,
                completedAt, message, writeBlockedReasons(blockedReasons));
    }

    public ShortTermScheduledSnapshot finishFinalBeforeDeadline(
            ShortTermSnapshotClaim claim,
            ShortTermReport report,
            Instant dataCutoffAt,
            Instant publicationDeadline,
            Clock clock,
            String readyMessage
    ) {
        Objects.requireNonNull(publicationDeadline, "publicationDeadline must not be null");
        Clock publicationClock = Objects.requireNonNull(clock, "clock must not be null");
        String reportJson = writeReport(report);
        String noBlockedReasons = writeBlockedReasons(List.of());
        String lateBlockedReasons = writeBlockedReasons(List.of("FINAL_DEADLINE_EXPIRED"));
        String reportPayloadHash = sha256(reportJson == null ? "" : reportJson);
        Instant publicationStartedAt = publicationClock.instant();
        if (publicationStartedAt.isAfter(publicationDeadline)) {
            return inFinalPublicationTransaction(() -> publishTerminal(
                    claim, ShortTermSnapshotStatus.DATA_BLOCKED, reportJson, dataCutoffAt,
                    publicationStartedAt, "尾盘终选落库超过完成截止时间", lateBlockedReasons));
        }

        inFinalPublicationTransaction(() -> stageFinalPayload(
                claim, reportJson, reportPayloadHash, dataCutoffAt, publicationStartedAt));
        return inFinalPublicationTransaction(() -> certifyPendingFinal(
                claim, reportPayloadHash, publicationDeadline, readyMessage,
                noBlockedReasons, lateBlockedReasons));
    }

    @Transactional
    public ShortTermScheduledSnapshot fail(
            ShortTermSnapshotClaim claim,
            Instant completedAt,
            String message,
            List<String> blockedReasons
    ) {
        return publishTerminal(
                claim, ShortTermSnapshotStatus.FAILED, null, null,
                completedAt, message, writeBlockedReasons(blockedReasons));
    }

    @Transactional(readOnly = true)
    public Optional<ShortTermScheduledSnapshot> latest(LocalDate tradeDate) {
        return repository.findFirstByTradeDateOrderByUpdatedAtDescSnapshotKeyDesc(tradeDate)
                .map(this::toSnapshot);
    }

    @Transactional(readOnly = true)
    public Optional<ShortTermScheduledSnapshot> latest(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage
    ) {
        return repository.findFirstByTradeDateAndStageOrderByUpdatedAtDescSnapshotKeyDesc(tradeDate, stage)
                .map(this::toSnapshot);
    }

    @Transactional(readOnly = true)
    public List<ShortTermScheduledSnapshot> running(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage
    ) {
        return repository.findAllByTradeDateAndStageAndStatusOrderByStartedAtAscSnapshotKeyAsc(
                        tradeDate, stage, ShortTermSnapshotStatus.RUNNING)
                .stream()
                .map(this::toSnapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShortTermScheduledSnapshot> pendingFinals(LocalDate tradeDate) {
        return repository.findAllByTradeDateAndStageAndStatusOrderByStartedAtAscSnapshotKeyAsc(
                        tradeDate, ShortTermSnapshotStage.FINAL, ShortTermSnapshotStatus.FINAL_PENDING)
                .stream()
                .map(this::toSnapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShortTermScheduledSnapshot> finalReady(LocalDate tradeDate) {
        return repository.findAllByTradeDateAndStageAndStatusOrderByStartedAtAscSnapshotKeyAsc(
                        tradeDate, ShortTermSnapshotStage.FINAL, ShortTermSnapshotStatus.FINAL_READY)
                .stream()
                .map(this::toSnapshot)
                .toList();
    }

    public ShortTermScheduledSnapshot expirePendingFinal(ShortTermScheduledSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.status() != ShortTermSnapshotStatus.FINAL_PENDING) {
            throw new IllegalArgumentException("Only FINAL_PENDING snapshots can be expired");
        }
        return inFinalPublicationTransaction(() -> blockPendingFinal(
                new ShortTermSnapshotClaim(snapshot.snapshotKey(), snapshot.attemptCount()),
                "尾盘结果认证中断，已失败关闭",
                writeBlockedReasons(List.of("FINAL_CERTIFICATION_INTERRUPTED"))));
    }

    public ShortTermScheduledSnapshot expireRunningFinal(ShortTermScheduledSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.stage() != ShortTermSnapshotStage.FINAL
                || snapshot.status() != ShortTermSnapshotStatus.RUNNING) {
            throw new IllegalArgumentException("Only RUNNING final snapshots can be expired");
        }
        return inFinalPublicationTransaction(() -> blockRunningFinal(
                new ShortTermSnapshotClaim(snapshot.snapshotKey(), snapshot.attemptCount()),
                "尾盘终选超过截止时间仍未完成",
                writeBlockedReasons(List.of("FINAL_DEADLINE_EXPIRED"))));
    }

    public ShortTermScheduledSnapshot expireUncertifiedFinal(
            ShortTermScheduledSnapshot snapshot,
            Instant publicationDeadline
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(publicationDeadline, "publicationDeadline must not be null");
        if (snapshot.stage() != ShortTermSnapshotStage.FINAL
                || snapshot.status() != ShortTermSnapshotStatus.FINAL_READY) {
            throw new IllegalArgumentException("Only FINAL_READY final snapshots can be proof-checked");
        }
        if (snapshot.hasCertifiedPublicationProof(publicationDeadline)) {
            return snapshot;
        }
        return inFinalPublicationTransaction(() -> blockUncertifiedFinal(
                new ShortTermSnapshotClaim(snapshot.snapshotKey(), snapshot.attemptCount()),
                publicationDeadline));
    }

    @Transactional(readOnly = true)
    public Optional<ShortTermScheduledSnapshot> find(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint
    ) {
        return repository.findByTradeDateAndStageAndParameterFingerprint(tradeDate, stage, parameterFingerprint)
                .map(this::toSnapshot);
    }

    private ShortTermScheduledSnapshot publishTerminal(
            ShortTermSnapshotClaim claim,
            ShortTermSnapshotStatus status,
            String reportJson,
            Instant dataCutoffAt,
            Instant completedAt,
            String message,
            String blockedReasonsJson
    ) {
        return publishTerminalFrom(
                claim, ShortTermSnapshotStatus.RUNNING, status, reportJson, dataCutoffAt,
                completedAt, message, blockedReasonsJson);
    }

    private ShortTermScheduledSnapshot publishTerminalFrom(
            ShortTermSnapshotClaim claim,
            ShortTermSnapshotStatus expectedStatus,
            ShortTermSnapshotStatus status,
            String reportJson,
            Instant dataCutoffAt,
            Instant completedAt,
            String message,
            String blockedReasonsJson
    ) {
        int updated = repository.publishTerminal(
                claim.snapshotKey(), claim.attemptCount(), expectedStatus,
                status, reportJson, dataCutoffAt,
                completedAt, message, blockedReasonsJson);
        if (updated != 1) {
            throw new IllegalStateException("Scheduled snapshot claim is stale or no longer running");
        }
        return toSnapshot(repository.findById(claim.snapshotKey())
                .orElseThrow(() -> new IllegalStateException("Scheduled snapshot was not claimed")));
    }

    private ShortTermScheduledSnapshot stageFinalPayload(
            ShortTermSnapshotClaim claim,
            String reportJson,
            String reportPayloadHash,
            Instant dataCutoffAt,
            Instant stagedAt
    ) {
        int updated = repository.stageFinalPayload(
                claim.snapshotKey(), claim.attemptCount(), ShortTermSnapshotStatus.RUNNING,
                ShortTermSnapshotStatus.FINAL_PENDING,
                reportJson, reportPayloadHash, dataCutoffAt, stagedAt, "尾盘终选正在提交认证");
        if (updated != 1) {
            throw new IllegalStateException("Scheduled snapshot claim changed while staging final payload");
        }
        return toSnapshot(repository.findById(claim.snapshotKey())
                .orElseThrow(() -> new IllegalStateException("Scheduled snapshot was not claimed")));
    }

    private ShortTermScheduledSnapshot certifyPendingFinal(
            ShortTermSnapshotClaim claim,
            String reportPayloadHash,
            Instant publicationDeadline,
            String readyMessage,
            String noBlockedReasons,
            String lateBlockedReasons
    ) {
        Instant certificationAt = databaseTime.get();
        int certified = repository.certifyPendingFinal(
                claim.snapshotKey(), claim.attemptCount(), ShortTermSnapshotStatus.FINAL_PENDING,
                ShortTermSnapshotStatus.FINAL_READY, reportPayloadHash, certificationAt,
                publicationDeadline,
                readyMessage, noBlockedReasons);
        if (certified == 1) {
            return currentSnapshot(claim.snapshotKey());
        }
        int blocked = repository.blockPendingFinal(
                claim.snapshotKey(), claim.attemptCount(), ShortTermSnapshotStatus.FINAL_PENDING,
                ShortTermSnapshotStatus.DATA_BLOCKED, certificationAt,
                "尾盘终选落库超过完成截止时间",
                lateBlockedReasons);
        if (blocked == 1) {
            return currentSnapshot(claim.snapshotKey());
        }
        ShortTermScheduledSnapshot current = currentSnapshot(claim.snapshotKey());
        if (current.status() == ShortTermSnapshotStatus.FINAL_READY
                || current.status() == ShortTermSnapshotStatus.DATA_BLOCKED) {
            return current;
        }
        throw new IllegalStateException("Scheduled final certification lost its pending claim");
    }

    private ShortTermScheduledSnapshot blockPendingFinal(
            ShortTermSnapshotClaim claim,
            String message,
            String blockedReasonsJson
    ) {
        Instant completedAt = databaseTime.get();
        int blocked = repository.blockPendingFinal(
                claim.snapshotKey(), claim.attemptCount(), ShortTermSnapshotStatus.FINAL_PENDING,
                ShortTermSnapshotStatus.DATA_BLOCKED, completedAt, message, blockedReasonsJson);
        if (blocked == 1) {
            return currentSnapshot(claim.snapshotKey());
        }
        ShortTermScheduledSnapshot current = currentSnapshot(claim.snapshotKey());
        if (FINAL_TERMINAL_STATUSES.contains(current.status())) {
            return current;
        }
        throw new IllegalStateException("Scheduled pending final is stale or no longer pending");
    }

    private ShortTermScheduledSnapshot blockRunningFinal(
            ShortTermSnapshotClaim claim,
            String message,
            String blockedReasonsJson
    ) {
        Instant completedAt = databaseTime.get();
        int blocked = repository.publishTerminal(
                claim.snapshotKey(), claim.attemptCount(), ShortTermSnapshotStatus.RUNNING,
                ShortTermSnapshotStatus.DATA_BLOCKED, null, null,
                completedAt, message, blockedReasonsJson);
        if (blocked == 1) {
            return currentSnapshot(claim.snapshotKey());
        }
        ShortTermScheduledSnapshot current = currentSnapshot(claim.snapshotKey());
        if (current.status() == ShortTermSnapshotStatus.FINAL_PENDING) {
            return blockPendingFinal(claim, "尾盘结果认证中断，已失败关闭",
                    writeBlockedReasons(List.of("FINAL_CERTIFICATION_INTERRUPTED")));
        }
        if (FINAL_TERMINAL_STATUSES.contains(current.status())) {
            return current;
        }
        throw new IllegalStateException("Scheduled running final is stale or no longer running");
    }

    private ShortTermScheduledSnapshot blockUncertifiedFinal(
            ShortTermSnapshotClaim claim,
            Instant publicationDeadline
    ) {
        Instant completedAt = databaseTime.get();
        int blocked = repository.blockUncertifiedFinal(
                claim.snapshotKey(), claim.attemptCount(), ShortTermSnapshotStage.FINAL,
                ShortTermSnapshotStatus.FINAL_READY, ShortTermSnapshotStatus.DATA_BLOCKED,
                publicationDeadline, completedAt, "尾盘终选缺少有效截止认证证明",
                writeBlockedReasons(List.of("FINAL_CERTIFICATION_PROOF_INVALID")));
        if (blocked == 1) {
            return currentSnapshot(claim.snapshotKey());
        }
        ShortTermScheduledSnapshot current = currentSnapshot(claim.snapshotKey());
        if (current.status() == ShortTermSnapshotStatus.DATA_BLOCKED
                || current.hasCertifiedPublicationProof(publicationDeadline)) {
            return current;
        }
        throw new IllegalStateException("Scheduled final proof check lost its FINAL_READY claim");
    }

    private ShortTermScheduledSnapshot currentSnapshot(String snapshotKey) {
        return toSnapshot(repository.findById(snapshotKey)
                .orElseThrow(() -> new IllegalStateException("Scheduled snapshot was not claimed")));
    }

    private <T> T inFinalPublicationTransaction(Supplier<T> operation) {
        T result = finalPublicationTransaction.execute(status -> operation.get());
        if (result == null) {
            throw new IllegalStateException("Scheduled final publication returned no result");
        }
        return result;
    }

    private ShortTermScheduledSnapshot toSnapshot(ShortTermScheduledSnapshotEntity entity) {
        return new ShortTermScheduledSnapshot(
                entity.getSnapshotKey(), entity.getTradeDate(), entity.getStage(), entity.getStatus(),
                entity.getAttemptCount(), entity.getParameterFingerprint(), entity.getParametersJson(),
                entity.getDataCutoffAt(),
                entity.getStartedAt(),
                entity.getCompletedAt(), entity.getMessage(), readBlockedReasons(entity.getBlockedReasonsJson()),
                readReport(entity.getReportJson()), entity.getReportPayloadHash(),
                entity.getPayloadCommittedByAt());
    }

    private ShortTermSnapshotClaim currentClaim(String snapshotKey) {
        ShortTermScheduledSnapshotEntity entity = repository.findById(snapshotKey)
                .orElseThrow(() -> new IllegalStateException("Reclaimed scheduled snapshot is missing"));
        return new ShortTermSnapshotClaim(snapshotKey, entity.getAttemptCount());
    }

    private ShortTermSnapshotStage claimedStage(ShortTermSnapshotClaim claim) {
        return repository.findById(claim.snapshotKey())
                .map(ShortTermScheduledSnapshotEntity::getStage)
                .orElseThrow(() -> new IllegalStateException("Scheduled snapshot was not claimed"));
    }

    private String validatedClaimSnapshotKey(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            String parametersJson,
            Instant startedAt
    ) {
        String snapshotKey = validatedSnapshotKey(tradeDate, stage, parameterFingerprint);
        if (parametersJson == null) {
            throw new IllegalArgumentException("parametersJson must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        return snapshotKey;
    }

    private String validatedSnapshotKey(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint
    ) {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        if (parameterFingerprint == null || parameterFingerprint.isBlank()) {
            throw new IllegalArgumentException("parameterFingerprint must not be blank");
        }
        if (parameterFingerprint.length() > PARAMETER_FINGERPRINT_MAX_LENGTH) {
            throw new IllegalArgumentException("parameterFingerprint exceeds schema length 64");
        }
        String snapshotKey = tradeDate + ":" + stage + ":" + parameterFingerprint;
        if (snapshotKey.length() > SNAPSHOT_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("snapshotKey exceeds schema length 160");
        }
        return snapshotKey;
    }

    private String writeReport(ShortTermReport report) {
        if (report == null) {
            return null;
        }
        return writeValue(report);
    }

    private String writeBlockedReasons(List<String> blockedReasons) {
        return writeValue(blockedReasons == null ? List.of() : blockedReasons);
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize scheduled snapshot", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ShortTermReport readReport(String reportJson) {
        if (reportJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(reportJson, ShortTermReport.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize scheduled snapshot report", exception);
        }
    }

    private List<String> readBlockedReasons(String blockedReasonsJson) {
        if (blockedReasonsJson == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(blockedReasonsJson, BLOCKED_REASONS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize scheduled snapshot blocked reasons", exception);
        }
    }
}

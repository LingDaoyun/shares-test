package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ShortTermScheduledSnapshotStore {

    private static final TypeReference<List<String>> BLOCKED_REASONS_TYPE = new TypeReference<>() { };
    private static final String RUNNING_MESSAGE = "正在执行";
    private static final Set<ShortTermSnapshotStatus> FINISH_STATUSES = Set.of(
            ShortTermSnapshotStatus.PRESELECT_READY,
            ShortTermSnapshotStatus.FINAL_READY,
            ShortTermSnapshotStatus.NO_TRADE,
            ShortTermSnapshotStatus.DATA_BLOCKED
    );

    private final ShortTermScheduledSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public ShortTermScheduledSnapshotStore(
            ShortTermScheduledSnapshotRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Optional<ShortTermSnapshotClaim> claim(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            String parametersJson,
            Instant startedAt
    ) {
        String snapshotKey = snapshotKey(tradeDate, stage, parameterFingerprint);
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
        return publishTerminal(
                claim, status, writeReport(report), dataCutoffAt,
                completedAt, message, writeBlockedReasons(blockedReasons));
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
        int updated = repository.publishTerminal(
                claim.snapshotKey(), claim.attemptCount(), ShortTermSnapshotStatus.RUNNING,
                status, reportJson, dataCutoffAt,
                completedAt, message, blockedReasonsJson);
        if (updated != 1) {
            throw new IllegalStateException("Scheduled snapshot claim is stale or no longer running");
        }
        return toSnapshot(repository.findById(claim.snapshotKey())
                .orElseThrow(() -> new IllegalStateException("Scheduled snapshot was not claimed")));
    }

    private ShortTermScheduledSnapshot toSnapshot(ShortTermScheduledSnapshotEntity entity) {
        return new ShortTermScheduledSnapshot(
                entity.getSnapshotKey(), entity.getTradeDate(), entity.getStage(), entity.getStatus(),
                entity.getParameterFingerprint(), entity.getDataCutoffAt(), entity.getStartedAt(),
                entity.getCompletedAt(), entity.getMessage(), readBlockedReasons(entity.getBlockedReasonsJson()),
                readReport(entity.getReportJson()));
    }

    private ShortTermSnapshotClaim currentClaim(String snapshotKey) {
        ShortTermScheduledSnapshotEntity entity = repository.findById(snapshotKey)
                .orElseThrow(() -> new IllegalStateException("Reclaimed scheduled snapshot is missing"));
        return new ShortTermSnapshotClaim(snapshotKey, entity.getAttemptCount());
    }

    private String snapshotKey(LocalDate tradeDate, ShortTermSnapshotStage stage, String parameterFingerprint) {
        return tradeDate + ":" + stage + ":" + parameterFingerprint;
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

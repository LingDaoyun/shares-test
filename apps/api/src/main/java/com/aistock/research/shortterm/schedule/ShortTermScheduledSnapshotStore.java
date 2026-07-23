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

@Service
public class ShortTermScheduledSnapshotStore {

    private static final TypeReference<List<String>> BLOCKED_REASONS_TYPE = new TypeReference<>() { };

    private final ShortTermScheduledSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public ShortTermScheduledSnapshotStore(
            ShortTermScheduledSnapshotRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public boolean claim(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            String parametersJson,
            Instant startedAt
    ) {
        String snapshotKey = snapshotKey(tradeDate, stage, parameterFingerprint);
        try {
            repository.saveAndFlush(new ShortTermScheduledSnapshotEntity(
                    snapshotKey, tradeDate, stage, parameterFingerprint, parametersJson, startedAt));
            return true;
        } catch (DataIntegrityViolationException ignored) {
            return repository.reclaimFailed(snapshotKey, ShortTermSnapshotStatus.RUNNING, ShortTermSnapshotStatus.FAILED) == 1;
        }
    }

    @Transactional
    public ShortTermScheduledSnapshot finish(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            ShortTermSnapshotStatus status,
            ShortTermReport report,
            Instant dataCutoffAt,
            Instant completedAt,
            String message,
            List<String> blockedReasons
    ) {
        ShortTermScheduledSnapshotEntity entity = requiredSnapshot(tradeDate, stage, parameterFingerprint);
        entity.finish(status, writeReport(report), dataCutoffAt, completedAt, message, writeBlockedReasons(blockedReasons));
        return toSnapshot(entity);
    }

    @Transactional
    public ShortTermScheduledSnapshot fail(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint,
            Instant completedAt,
            String message,
            List<String> blockedReasons
    ) {
        ShortTermScheduledSnapshotEntity entity = requiredSnapshot(tradeDate, stage, parameterFingerprint);
        entity.finish(ShortTermSnapshotStatus.FAILED, null, null, completedAt, message, writeBlockedReasons(blockedReasons));
        return toSnapshot(entity);
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

    private ShortTermScheduledSnapshotEntity requiredSnapshot(
            LocalDate tradeDate,
            ShortTermSnapshotStage stage,
            String parameterFingerprint
    ) {
        return repository.findById(snapshotKey(tradeDate, stage, parameterFingerprint))
                .orElseThrow(() -> new IllegalStateException("Scheduled snapshot was not claimed"));
    }

    private ShortTermScheduledSnapshot toSnapshot(ShortTermScheduledSnapshotEntity entity) {
        return new ShortTermScheduledSnapshot(
                entity.getSnapshotKey(), entity.getTradeDate(), entity.getStage(), entity.getStatus(),
                entity.getParameterFingerprint(), entity.getDataCutoffAt(), entity.getStartedAt(),
                entity.getCompletedAt(), entity.getMessage(), readBlockedReasons(entity.getBlockedReasonsJson()),
                readReport(entity.getReportJson()));
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

package com.aistock.research.shortterm.leader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class JpaShortTermLeaderSnapshotStore implements ShortTermLeaderSnapshotStore {

    private final ShortTermLeaderSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public JpaShortTermLeaderSnapshotStore(
            ShortTermLeaderSnapshotRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShortTermLeaderSnapshot> latestSameDayBefore(
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt
    ) {
        return repository
                .findFirstByRuleVersionAndTradeDateAndCapturedAtLessThanOrderByCapturedAtDescSnapshotIdDesc(
                        ruleVersion, tradeDate, capturedAt)
                .map(this::deserialize);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShortTermLeaderSnapshot> latestBeforeTradeDate(
            String ruleVersion,
            LocalDate tradeDate
    ) {
        return repository
                .findFirstByRuleVersionAndTradeDateLessThanOrderByTradeDateDescCapturedAtDescSnapshotIdDesc(
                        ruleVersion, tradeDate)
                .map(this::deserialize);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShortTermLeaderCheckpoint> sameDayCheckpointsBefore(
            String ruleVersion,
            LocalDate tradeDate,
            Instant capturedAt
    ) {
        return repository
                .findByRuleVersionAndTradeDateAndCapturedAtLessThanOrderByCapturedAtAscSnapshotIdAsc(
                        ruleVersion, tradeDate, capturedAt)
                .stream()
                .map(this::deserializeCheckpoint)
                .toList();
    }

    @Override
    @Transactional
    public void save(ShortTermLeaderSnapshot snapshot) {
        saveEntity(snapshot, null);
    }

    @Override
    @Transactional
    public void saveCheckpoint(
            ShortTermLeaderSnapshot snapshot,
            ShortTermLeaderRisk risk
    ) {
        Objects.requireNonNull(risk, "risk");
        saveEntity(snapshot, risk);
    }

    private void saveEntity(
            ShortTermLeaderSnapshot snapshot,
            ShortTermLeaderRisk risk
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        String snapshotId = snapshot.snapshotId();
        repository.save(new ShortTermLeaderSnapshotEntity(
                snapshotId,
                snapshot.ruleVersion(),
                snapshot.tradeDate(),
                snapshot.capturedAt(),
                serialize(snapshot, snapshotId),
                risk == null ? null : serializeRisk(risk, snapshotId),
                Instant.now()
        ));
    }

    private String serializeRisk(ShortTermLeaderRisk risk, String snapshotId) {
        try {
            return objectMapper.writeValueAsString(risk);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize short-term leader risk: " + snapshotId,
                    exception
            );
        }
    }

    private String serialize(ShortTermLeaderSnapshot snapshot, String snapshotId) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize short-term leader snapshot: " + snapshotId,
                    exception
            );
        }
    }

    private ShortTermLeaderSnapshot deserialize(ShortTermLeaderSnapshotEntity entity) {
        try {
            return objectMapper.readValue(
                    entity.getSnapshotJson(),
                    ShortTermLeaderSnapshot.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to deserialize short-term leader snapshot: " + entity.getSnapshotId(),
                    exception
            );
        }
    }

    private ShortTermLeaderCheckpoint deserializeCheckpoint(
            ShortTermLeaderSnapshotEntity entity
    ) {
        ShortTermLeaderSnapshot snapshot = deserialize(entity);
        String riskJson = entity.getRiskJson();
        if (riskJson == null || riskJson.isBlank()) {
            return new ShortTermLeaderCheckpoint(snapshot, null);
        }
        try {
            return new ShortTermLeaderCheckpoint(
                    snapshot,
                    objectMapper.readValue(riskJson, ShortTermLeaderRisk.class)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to deserialize short-term leader risk: " + entity.getSnapshotId(),
                    exception
            );
        }
    }
}

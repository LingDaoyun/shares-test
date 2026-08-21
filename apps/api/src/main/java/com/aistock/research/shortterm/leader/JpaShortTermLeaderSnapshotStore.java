package com.aistock.research.shortterm.leader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
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
    @Transactional
    public void save(ShortTermLeaderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String snapshotId = snapshot.snapshotId();
        repository.save(new ShortTermLeaderSnapshotEntity(
                snapshotId,
                snapshot.ruleVersion(),
                snapshot.tradeDate(),
                snapshot.capturedAt(),
                serialize(snapshot, snapshotId),
                Instant.now()
        ));
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
}

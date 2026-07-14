package com.aistock.research.v2.decision;

import com.aistock.research.v2.strategy.StrategySignal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class V2RecommendationLedgerService {

    private final V2RecommendationLedgerRepository repository;
    private final ObjectMapper canonicalObjectMapper;
    private final TransactionTemplate createLedgerTransaction;
    private final ConcurrentMap<String, LockHolder> fingerprintLocks = new ConcurrentHashMap<>();

    public V2RecommendationLedgerService(
            V2RecommendationLedgerRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.canonicalObjectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.createLedgerTransaction = new TransactionTemplate(transactionManager);
        this.createLedgerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public V2RecommendationLedgerEntity record(StrategySignal signal) {
        String payloadJson = toJson(signal);
        String fingerprint = sha256(signal.strategyCode() + "|" + signal.strategyVersion() + "|"
                + signal.symbol() + "|" + signal.decisionAt() + "|" + payloadJson);
        Optional<V2RecommendationLedgerEntity> existing = repository.findByRecommendationFingerprint(fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }
        LockHolder lockHolder = acquireFingerprintLock(fingerprint);
        try {
            try {
                return createLedgerTransaction.execute(status -> createOrFind(signal, payloadJson, fingerprint));
            } catch (DataIntegrityViolationException exception) {
                return repository.findByRecommendationFingerprint(fingerprint)
                        .orElseThrow(() -> exception);
            }
        } finally {
            releaseFingerprintLock(fingerprint, lockHolder);
        }
    }

    private LockHolder acquireFingerprintLock(String fingerprint) {
        LockHolder lockHolder = fingerprintLocks.compute(fingerprint, (key, current) -> {
            LockHolder holder = current == null ? new LockHolder() : current;
            holder.references++;
            return holder;
        });
        lockHolder.lock.lock();
        return lockHolder;
    }

    private void releaseFingerprintLock(String fingerprint, LockHolder lockHolder) {
        lockHolder.lock.unlock();
        fingerprintLocks.computeIfPresent(fingerprint, (key, current) -> {
            if (current != lockHolder) {
                return current;
            }
            lockHolder.references--;
            return lockHolder.references == 0 ? null : lockHolder;
        });
    }

    private static final class LockHolder {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    private V2RecommendationLedgerEntity createOrFind(
            StrategySignal signal,
            String payloadJson,
            String fingerprint
    ) {
        Optional<V2RecommendationLedgerEntity> existing = repository.findByRecommendationFingerprint(fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }
        return repository.saveAndFlush(new V2RecommendationLedgerEntity(
                sha256("ledger|" + fingerprint),
                fingerprint,
                signal.strategyCode().name(),
                signal.strategyVersion(),
                signal.symbol(),
                signal.companyName(),
                signal.decisionAt(),
                signal.dataCutoffAt(),
                signal.candidateStage().name(),
                signal.action().name(),
                signal.rankScore(),
                signal.dataConfidence(),
                signal.historicalHitRate(),
                signal.riskReward(),
                payloadJson,
                Instant.now()));
    }

    @Transactional(readOnly = true)
    public Optional<V2RecommendationLedgerEntity> latest(String symbol) {
        return repository.findFirstBySymbolOrderByDecisionAtDescLedgerIdDesc(symbol);
    }

    private String toJson(StrategySignal signal) {
        try {
            ObjectNode payload = canonicalObjectMapper.valueToTree(signal);
            canonicalizeReplayDecimals(payload.path("replayPayload"));
            formatPersistedScale(payload, "rankScore", 2);
            formatPersistedScale(payload, "dataConfidence", 2);
            formatPersistedScale(payload, "historicalHitRate", 2);
            formatPersistedScale(payload, "riskReward", 2);
            formatPersistedScale(payload, "positionLimit", 4);
            return canonicalObjectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize strategy signal", ex);
        }
    }

    private static void canonicalizeReplayDecimals(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isBigDecimal()) {
                    objectNode.set(entry.getKey(), DecimalNode.valueOf(toPlainDecimal(value.decimalValue())));
                } else {
                    canonicalizeReplayDecimals(value);
                }
            });
        } else if (node instanceof ArrayNode arrayNode) {
            for (int index = 0; index < arrayNode.size(); index++) {
                JsonNode value = arrayNode.get(index);
                if (value.isBigDecimal()) {
                    arrayNode.set(index, DecimalNode.valueOf(toPlainDecimal(value.decimalValue())));
                } else {
                    canonicalizeReplayDecimals(value);
                }
            }
        }
    }

    private static BigDecimal toPlainDecimal(BigDecimal value) {
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    private static void formatPersistedScale(ObjectNode payload, String field, int scale) {
        JsonNode value = payload.get(field);
        if (value != null && !value.isNull()) {
            payload.set(field, DecimalNode.valueOf(value.decimalValue().setScale(scale)));
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}

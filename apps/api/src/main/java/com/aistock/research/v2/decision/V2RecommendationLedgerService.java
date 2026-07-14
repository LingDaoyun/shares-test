package com.aistock.research.v2.decision;

import com.aistock.research.v2.strategy.StrategySignal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class V2RecommendationLedgerService {

    private final V2RecommendationLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate createLedgerTransaction;

    public V2RecommendationLedgerService(
            V2RecommendationLedgerRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
        try {
            return createLedgerTransaction.execute(status -> createOrFind(signal, payloadJson, fingerprint));
        } catch (DataIntegrityViolationException exception) {
            return repository.findByRecommendationFingerprint(fingerprint)
                    .orElseThrow(() -> exception);
        }
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
            return objectMapper.writeValueAsString(signal);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize strategy signal", ex);
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

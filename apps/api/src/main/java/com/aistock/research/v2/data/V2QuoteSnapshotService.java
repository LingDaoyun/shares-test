package com.aistock.research.v2.data;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class V2QuoteSnapshotService {

    private final V2QuoteSnapshotRepository repository;

    public V2QuoteSnapshotService(V2QuoteSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public V2QuoteSnapshotEntity record(String symbol, String companyName, QuoteStage quoteStage,
                                        BigDecimal lastPrice, BigDecimal amount,
                                        Instant effectiveAt, Instant availableAt, Instant ingestedAt,
                                        String source, String sourceVersion,
                                        DataQualityStatus qualityStatus, String rawPayloadJson) {
        String rawHash = sha256(rawPayloadJson);
        String snapshotId = sha256(symbol + "|" + quoteStage + "|" + availableAt + "|" + source + "|" + sourceVersion + "|" + rawHash);
        return repository.save(new V2QuoteSnapshotEntity(
                snapshotId,
                symbol,
                companyName,
                quoteStage,
                lastPrice,
                amount,
                effectiveAt,
                availableAt,
                ingestedAt,
                source,
                sourceVersion,
                qualityStatus,
                rawHash,
                rawPayloadJson));
    }

    @Transactional(readOnly = true)
    public Optional<V2QuoteSnapshotEntity> latestVisible(String symbol, QuoteStage quoteStage, Instant decisionAt) {
        return repository.findFirstBySymbolAndQuoteStageAndAvailableAtLessThanEqualOrderByAvailableAtDescIngestedAtDesc(
                symbol, quoteStage, decisionAt);
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

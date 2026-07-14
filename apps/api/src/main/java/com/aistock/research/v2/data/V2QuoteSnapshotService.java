package com.aistock.research.v2.data;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
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
        validateChronology(effectiveAt, availableAt, ingestedAt);
        String rawHash = sha256(rawPayloadJson);
        String snapshotId = snapshotId(symbol, companyName, quoteStage, lastPrice, amount, effectiveAt, availableAt,
                ingestedAt, source, sourceVersion, qualityStatus, rawHash);
        return repository.findById(snapshotId).orElseGet(() -> repository.save(new V2QuoteSnapshotEntity(
                snapshotId, symbol, companyName, quoteStage, lastPrice, amount, effectiveAt, availableAt, ingestedAt,
                source, sourceVersion, qualityStatus, rawHash, rawPayloadJson)));
    }

    @Transactional(readOnly = true)
    public Optional<V2QuoteSnapshotEntity> latestVisible(String symbol, QuoteStage quoteStage, Instant decisionAt) {
        return repository.findFirstBySymbolAndQuoteStageAndAvailableAtLessThanEqualAndIngestedAtLessThanEqualOrderByAvailableAtDescIngestedAtDesc(
                symbol, quoteStage, decisionAt, decisionAt);
    }

    private static void validateChronology(Instant effectiveAt, Instant availableAt, Instant ingestedAt) {
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(ingestedAt, "ingestedAt must not be null");
        if (effectiveAt.isAfter(availableAt) || availableAt.isAfter(ingestedAt)) {
            throw new IllegalArgumentException("quote snapshot chronology must satisfy effectiveAt <= availableAt <= ingestedAt");
        }
    }

    private static String snapshotId(
            String symbol,
            String companyName,
            QuoteStage quoteStage,
            BigDecimal lastPrice,
            BigDecimal amount,
            Instant effectiveAt,
            Instant availableAt,
            Instant ingestedAt,
            String source,
            String sourceVersion,
            DataQualityStatus qualityStatus,
            String rawPayloadHash
    ) {
        return sha256(String.join("|",
                Objects.requireNonNull(symbol, "symbol must not be null"),
                Objects.requireNonNull(companyName, "companyName must not be null"),
                Objects.requireNonNull(quoteStage, "quoteStage must not be null").name(),
                decimalIdentity(lastPrice),
                decimalIdentity(amount),
                effectiveAt.toString(),
                availableAt.toString(),
                ingestedAt.toString(),
                Objects.requireNonNull(source, "source must not be null"),
                Objects.requireNonNull(sourceVersion, "sourceVersion must not be null"),
                Objects.requireNonNull(qualityStatus, "qualityStatus must not be null").name(),
                rawPayloadHash));
    }

    private static String decimalIdentity(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Objects.requireNonNull(value, "value must not be null")
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}

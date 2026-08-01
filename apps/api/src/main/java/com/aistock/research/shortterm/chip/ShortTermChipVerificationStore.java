package com.aistock.research.shortterm.chip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class ShortTermChipVerificationStore {

    private final ShortTermChipVerificationRepository repository;
    private final ObjectMapper objectMapper;

    public ShortTermChipVerificationStore(
            ShortTermChipVerificationRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ShortTermChipVerificationEvidence save(
            String symbol,
            LocalDate tradeDate,
            String modelVersion,
            ShortTermChipSnapshot snapshot,
            ExternalChipPerformance external,
            Instant dataCutoffAt,
            Instant observedAt,
            String errorSummary
    ) {
        validateKey(symbol, tradeDate, modelVersion);
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Instant safeObservedAt = observedAt == null ? Instant.now() : observedAt;
        String safeError = sanitize(errorSummary);
        String verificationKey = key(symbol, tradeDate, modelVersion);
        ShortTermChipVerificationEntity entity = new ShortTermChipVerificationEntity(
                verificationKey,
                symbol,
                tradeDate,
                modelVersion,
                snapshot,
                write(snapshot),
                external == null ? null : write(external),
                dataCutoffAt,
                safeObservedAt,
                safeError
        );
        if (repository.existsById(verificationKey)) {
            entity.markExisting();
        }
        repository.save(entity);
        return new ShortTermChipVerificationEvidence(
                symbol, tradeDate, modelVersion, snapshot, external,
                dataCutoffAt, safeObservedAt, safeError);
    }

    @Transactional(readOnly = true)
    public Optional<ShortTermChipVerificationEvidence> find(
            String symbol,
            LocalDate tradeDate,
            String modelVersion
    ) {
        validateKey(symbol, tradeDate, modelVersion);
        return repository.findBySymbolAndTradeDateAndModelVersion(symbol, tradeDate, modelVersion)
                .map(this::read);
    }

    private ShortTermChipVerificationEvidence read(ShortTermChipVerificationEntity entity) {
        return new ShortTermChipVerificationEvidence(
                entity.getSymbol(),
                entity.getTradeDate(),
                entity.getModelVersion(),
                read(entity.getSnapshotJson(), ShortTermChipSnapshot.class),
                entity.getExternalSummaryJson() == null
                        ? null
                        : read(entity.getExternalSummaryJson(), ExternalChipPerformance.class),
                entity.getDataCutoffAt(),
                entity.getObservedAt(),
                entity.getErrorSummary()
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize chip verification evidence", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize chip verification evidence", exception);
        }
    }

    private void validateKey(String symbol, LocalDate tradeDate, String modelVersion) {
        if (symbol == null || !symbol.matches("\\d{6}")) {
            throw new IllegalArgumentException("symbol must be a six digit A-share code");
        }
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        if (modelVersion == null || modelVersion.isBlank() || modelVersion.length() > 80) {
            throw new IllegalArgumentException("modelVersion must fit schema length 80");
        }
    }

    private String key(String symbol, LocalDate tradeDate, String modelVersion) {
        return symbol + ":" + tradeDate + ":" + modelVersion;
    }

    private String sanitize(String errorSummary) {
        String sanitized = ChipEvidenceSanitizer.sanitize(errorSummary);
        if (sanitized == null) {
            return null;
        }
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }
}

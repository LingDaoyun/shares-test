package com.aistock.research.history;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KlineHistoryRecorder {

    private static final String BAR_TYPE = "DAY_QFQ";

    private final KlineHistoryRepository repository;

    public KlineHistoryRecorder(KlineHistoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(List<EastMoneyKLine> rows, String sourceName) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Instant observedAt = Instant.now();
        Map<String, KlineHistoryEntity> unique = new LinkedHashMap<>();
        for (EastMoneyKLine row : rows) {
            if (row == null || row.symbol() == null || row.tradeDate() == null) {
                continue;
            }
            String observationId = fingerprint(row);
            unique.putIfAbsent(observationId, new KlineHistoryEntity(
                    observationId,
                    row.symbol(),
                    row.tradeDate(),
                    BAR_TYPE,
                    row.open(),
                    row.close(),
                    row.high(),
                    row.low(),
                    row.volume(),
                    row.amount(),
                    sourceName == null || sourceName.isBlank() ? "未知行情源" : sourceName,
                    observedAt
            ));
        }
        if (unique.isEmpty()) {
            return;
        }
        Map<String, List<KlineHistoryEntity>> bySymbol = unique.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        KlineHistoryEntity::getSymbol,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
        for (Map.Entry<String, List<KlineHistoryEntity>> entry : bySymbol.entrySet()) {
            List<KlineHistoryEntity> candidates = entry.getValue();
            LocalDateRange range = dateRange(candidates);
            HashSet<String> existingIds = new HashSet<>(repository.findObservationIdsBySymbolAndTradeDateBetween(
                    entry.getKey(), range.start(), range.end()
            ));
            List<KlineHistoryEntity> additions = candidates.stream()
                    .filter(entity -> !existingIds.contains(entity.getObservationId()))
                    .toList();
            if (!additions.isEmpty()) {
                repository.saveAll(additions);
            }
        }
    }

    private LocalDateRange dateRange(List<KlineHistoryEntity> entities) {
        java.time.LocalDate start = entities.stream()
                .map(KlineHistoryEntity::getTradeDate)
                .min(java.time.LocalDate::compareTo)
                .orElseThrow();
        java.time.LocalDate end = entities.stream()
                .map(KlineHistoryEntity::getTradeDate)
                .max(java.time.LocalDate::compareTo)
                .orElseThrow();
        return new LocalDateRange(start, end);
    }

    private String fingerprint(EastMoneyKLine row) {
        String canonical = String.join("|",
                row.symbol(),
                row.tradeDate().toString(),
                value(row.open()),
                value(row.close()),
                value(row.high()),
                value(row.low()),
                value(row.volume()),
                value(row.amount()),
                BAR_TYPE
        );
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 K 线版本指纹", exception);
        }
    }

    private String value(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private record LocalDateRange(java.time.LocalDate start, java.time.LocalDate end) {
    }
}

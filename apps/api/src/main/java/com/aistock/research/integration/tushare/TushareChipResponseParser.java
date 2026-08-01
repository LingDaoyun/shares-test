package com.aistock.research.integration.tushare;

import com.aistock.research.shortterm.chip.ExternalChipPerformance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class TushareChipResponseParser {

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String[] REQUIRED_FIELDS = {
            "ts_code", "trade_date", "cost_5pct", "cost_15pct", "cost_50pct",
            "cost_85pct", "cost_95pct", "weight_avg", "winner_rate"
    };

    private final ObjectMapper objectMapper;

    TushareChipResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ParseResult parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                return ParseResult.failure("Tushare筹码接口返回业务错误 code=" + code);
            }
            JsonNode fields = root.path("data").path("fields");
            JsonNode items = root.path("data").path("items");
            if (!fields.isArray() || !items.isArray() || items.isEmpty()) {
                return ParseResult.failure("Tushare筹码数据为空");
            }
            Map<String, Integer> indexes = indexes(fields);
            for (String field : REQUIRED_FIELDS) {
                if (!indexes.containsKey(field)) {
                    return ParseResult.failure("Tushare筹码字段不完整");
                }
            }
            JsonNode row = items.get(0);
            if (!row.isArray()) {
                return ParseResult.failure("Tushare筹码数据格式异常");
            }
            BigDecimal winnerRate = decimal(row, indexes.get("winner_rate"));
            if (winnerRate == null || winnerRate.compareTo(BigDecimal.ZERO) < 0
                    || winnerRate.compareTo(new BigDecimal("100")) > 0) {
                return ParseResult.failure("Tushare胜率单位或范围异常");
            }
            ExternalChipPerformance value = new ExternalChipPerformance(
                    symbol(row, indexes.get("ts_code")),
                    LocalDate.parse(text(row, indexes.get("trade_date")), BASIC_DATE),
                    decimal(row, indexes.get("cost_5pct")),
                    decimal(row, indexes.get("cost_15pct")),
                    decimal(row, indexes.get("cost_50pct")),
                    decimal(row, indexes.get("cost_85pct")),
                    decimal(row, indexes.get("cost_95pct")),
                    decimal(row, indexes.get("weight_avg")),
                    winnerRate,
                    "Tushare cyq_perf",
                    Instant.now()
            );
            if (!value.completeForVerification()) {
                return ParseResult.failure("Tushare筹码字段不完整");
            }
            return ParseResult.success(value);
        } catch (RuntimeException exception) {
            return ParseResult.failure("Tushare筹码响应解析失败");
        } catch (Exception exception) {
            return ParseResult.failure("Tushare筹码响应解析失败");
        }
    }

    private Map<String, Integer> indexes(JsonNode fields) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            indexes.put(fields.get(index).asText(), index);
        }
        return indexes;
    }

    private String symbol(JsonNode row, int index) {
        String tsCode = text(row, index);
        int suffix = tsCode.indexOf('.');
        return suffix < 0 ? tsCode : tsCode.substring(0, suffix);
    }

    private String text(JsonNode row, int index) {
        if (index < 0 || index >= row.size() || row.get(index).isNull()) {
            return null;
        }
        return row.get(index).asText();
    }

    private BigDecimal decimal(JsonNode row, int index) {
        String text = text(row, index);
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text);
    }

    record ParseResult(Optional<ExternalChipPerformance> value, String errorSummary) {
        static ParseResult success(ExternalChipPerformance value) {
            return new ParseResult(Optional.of(value), null);
        }

        static ParseResult failure(String errorSummary) {
            return new ParseResult(Optional.empty(), errorSummary);
        }
    }
}

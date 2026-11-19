package com.aistock.research.shortterm;

import com.aistock.research.shortterm.LimitBoardContextSnapshot.Context;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

@Service
public class LimitBoardContextService {
    private static final String SOURCE = "DayTrading Monster（同花顺异动解读）";
    private static final String SOURCE_URL = "https://daytrading.monster/api-docs/chinastock-anomaly";
    private static final String API_URL = "https://daytrading.monster/api/chinastock-anomaly";
    private static final int MAX_REASON_LENGTH = 128;

    private final ObjectMapper mapper;
    private final JsonFactory jsonFactory;
    private final ShortTermLimitUpBoardService boardService;
    private final Map<LocalDate, Cached> cache = new ConcurrentHashMap<>();

    public LimitBoardContextService(ObjectMapper mapper, ShortTermLimitUpBoardService boardService) {
        this.mapper = mapper;
        this.boardService = boardService;
        this.jsonFactory = mapper.getFactory().copy().setStreamReadConstraints(
                StreamReadConstraints.builder().maxDocumentLength(2 * 1024 * 1024).build());
    }

    public LimitBoardContextSnapshot snapshot(LocalDate date) {
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Cached cached = cache.get(date);
        if (cached != null) return cached.snapshot();
        ShortTermLimitUpBoardSnapshot board = boardService.cachedSnapshot(date);
        if (board == null || (board.stocks().isEmpty() && board.limitDownStocks().isEmpty())) return empty(date);
        return cache.computeIfAbsent(date, ignored -> {
            Load result = fetch(date, board);
            return new Cached(result.snapshot(), Instant.now().plusSeconds(result.complete() ? 120 : 20));
        }).snapshot();
    }

    private Load fetch(LocalDate date, ShortTermLimitUpBoardSnapshot board) {
        Process process;
        try {
            // The public response contains large unrelated sections. Stream only the needed analysis rows.
            process = new ProcessBuilder("curl", "-fsS", "--compressed", "--connect-timeout", "5", "--max-time", "38", API_URL)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException failure) {
            return new Load(empty(date), false);
        }
        try (InputStream input = process.getInputStream()) {
            Set<String> up = new HashSet<>();
            Set<String> down = new HashSet<>();
            board.stocks().forEach(stock -> up.add(stock.symbol()));
            board.limitDownStocks().forEach(stock -> down.add(stock.symbol()));
            return read(input, date, up, down);
        } catch (IOException failure) {
            return new Load(empty(date), false);
        } finally {
            process.destroy();
        }
    }

    Load read(InputStream input, LocalDate date, Set<String> upSymbols, Set<String> downSymbols) {
        Map<String, Context> up = new HashMap<>();
        Map<String, Context> down = new HashMap<>();
        boolean complete = false;
        try (JsonParser parser = jsonFactory.createParser(input)) {
            if (parser.nextToken() == JsonToken.START_OBJECT && findDatedSection(parser, date, "latest", "ok", true)
                    && findObject(parser, "anomaly")
                    && findDatedSection(parser, date, "anomalies", "available", false)) {
                complete = readRows(parser, up, down, new HashSet<>(upSymbols), new HashSet<>(downSymbols));
            }
        } catch (IOException failure) {
            // Fully parsed rows still carry the validated source and trading day if the transfer stops midway.
        }
        return new Load(new LimitBoardContextSnapshot(date, SOURCE, SOURCE_URL, up, down), complete);
    }

    /** The published response places snapshot metadata before its nested data sections. */
    private static boolean findDatedSection(JsonParser parser, LocalDate date, String section, String statusField,
                                            boolean root) throws IOException {
        String expectedDate = date.toString();
        boolean matchingDate = false;
        boolean available = false;
        boolean supported = false;
        JsonToken expectedToken = root ? JsonToken.START_OBJECT : JsonToken.START_ARRAY;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken token = parser.nextToken();
            if (field.equals(section)) return matchingDate && available && supported && token == expectedToken;
            if (field.equals("date")) matchingDate = token == JsonToken.VALUE_STRING && expectedDate.equals(parser.getText());
            if (field.equals(statusField)) available = token == JsonToken.VALUE_TRUE;
            if (root && field.equals("schemaVersion")) supported = token == JsonToken.VALUE_NUMBER_INT && parser.getIntValue() == 1;
            if (!root && field.equals("stale")) supported = token == JsonToken.VALUE_FALSE;
            parser.skipChildren();
        }
        return false;
    }

    private static boolean findObject(JsonParser parser, String name) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken token = parser.nextToken();
            if (field.equals(name)) return token == JsonToken.START_OBJECT;
            parser.skipChildren();
        }
        return false;
    }

    private boolean readRows(JsonParser parser, Map<String, Context> up, Map<String, Context> down,
                             Set<String> pendingUp, Set<String> pendingDown) throws IOException {
        boolean knownPool = !pendingUp.isEmpty() || !pendingDown.isEmpty();
        JsonToken token;
        while ((token = parser.nextToken()) == JsonToken.START_OBJECT) {
            JsonNode row = mapper.readTree(parser);
            String symbol = row.path("ticker").asText();
            if (!symbol.matches("[0-9]{6}")) continue;

            String tag = row.path("tag").asText();
            Map<String, Context> pool = switch (tag) {
                case "涨停", "大涨" -> up;
                case "跌停", "大跌" -> down;
                default -> null;
            };
            if (pool == null) continue;
            var topics = StreamSupport.stream(row.path("keywords").spliterator(), false).filter(JsonNode::isTextual).map(JsonNode::asText)
                    .map(String::strip).filter(topic -> !topic.isBlank()).distinct().limit(6).toList();
            String reason = excerpt(row.path("analysis").asText());
            if (topics.isEmpty() && reason == null) continue;

            Context context = new Context(topics, reason);
            if (tag.equals("涨停") || tag.equals("跌停")) {
                pool.put(symbol, context);
                if (!topics.isEmpty() && reason != null) {
                    (tag.equals("涨停") ? pendingUp : pendingDown).remove(symbol);
                }
            } else {
                pool.putIfAbsent(symbol, context);
            }
            if (knownPool && pendingUp.isEmpty() && pendingDown.isEmpty()) return true;
        }
        return token == JsonToken.END_ARRAY;
    }

    private static String excerpt(String analysis) {
        String text = analysis.lines().map(String::strip)
                .filter(line -> !line.isBlank() && !line.endsWith("：") && !line.startsWith("（免责声明"))
                .findFirst().orElse("").replaceFirst("^\\d+[、.]\\s*", "");
        if (text.isBlank()) return null;
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH) + "…";
    }

    private static LimitBoardContextSnapshot empty(LocalDate date) {
        return new LimitBoardContextSnapshot(date, SOURCE, SOURCE_URL, Map.of(), Map.of());
    }

    record Load(LimitBoardContextSnapshot snapshot, boolean complete) {}

    private record Cached(LimitBoardContextSnapshot snapshot, Instant expiresAt) {}
}

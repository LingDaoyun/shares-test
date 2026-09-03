package com.aistock.research.leader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LeaderSnapshotStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LeaderSnapshotStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<UUID> findSnapshotId(LocalDate tradeDate, String ruleVersion) {
        return jdbc.query("""
                SELECT id
                FROM leader_daily_snapshot
                WHERE trade_date = ? AND rule_version = ?
                """, (rows, rowNumber) -> rows.getObject("id", UUID.class),
                tradeDate, ruleVersion).stream().findFirst();
    }

    @Transactional
    public UUID saveSnapshotIfAbsent(PotentialLeaderSnapshot snapshot) {
        Optional<UUID> existing = findSnapshotId(snapshot.tradeDate(), snapshot.ruleVersion());
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        jdbc.update("""
                INSERT INTO leader_daily_snapshot (
                  id, trade_date, rule_version, quote_trade_date, quote_timestamp,
                  dragon_tiger_date, market_summary_json, directions_json,
                  potential_candidates_json, competition_candidates_json,
                  source_status_json, data_gaps_json, snapshot_hash, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                          ?::jsonb, ?::jsonb, ?, ?)
                ON CONFLICT (trade_date, rule_version) DO NOTHING
                """,
                id, snapshot.tradeDate(), snapshot.ruleVersion(), snapshot.quoteTradeDate(),
                Timestamp.from(snapshot.quoteTimestamp()), snapshot.dragonTigerDate(),
                json(snapshot.marketSummary()), json(snapshot.directions()),
                json(snapshot.potentialCandidates()), json(snapshot.competitionCandidates()),
                json(new SourceStatus(snapshot.status(), snapshot.boardOptions(), snapshot.coverage(),
                        snapshot.autoRefreshAllowed())),
                json(snapshot.dataGaps()), snapshotHash(snapshot), Timestamp.from(createdAt));
        return findSnapshotId(snapshot.tradeDate(), snapshot.ruleVersion())
                .orElseThrow(() -> new IllegalStateException("潜力龙头收盘快照写入失败"));
    }

    @Transactional
    public void initializeOutcomes(UUID snapshotId, PotentialLeaderSnapshot snapshot) {
        List<PotentialLeaderSnapshot.Candidate> candidates = new ArrayList<>();
        candidates.addAll(snapshot.potentialCandidates());
        candidates.addAll(snapshot.competitionCandidates());
        if (candidates.isEmpty()) {
            return;
        }
        List<Object[]> rows = candidates.stream().limit(20).map(candidate -> {
            PotentialLeaderSnapshot.Direction direction = snapshot.directions().stream()
                    .filter(value -> value.industry().equals(candidate.industry()))
                    .findFirst().orElse(null);
            String heat = direction != null && direction.score().compareTo(new BigDecimal("75")) >= 0
                    ? "HOT" : "WARM";
            return new Object[]{
                    snapshotId, candidate.lane().name(), normalizeSymbol(candidate.symbol()),
                    snapshot.tradeDate(), snapshot.ruleVersion(), candidate.grade().name(), heat,
                    "PENDING_DATA", Timestamp.from(Instant.now())
            };
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO leader_outcome (
                  snapshot_id, lane, symbol, trade_date, rule_version, grade,
                  direction_heat, status, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (snapshot_id, lane, symbol) DO NOTHING
                """, rows);
    }

    public List<PendingOutcome> pendingOutcomes() {
        return jdbc.query("""
                SELECT o.snapshot_id, o.lane, o.symbol, o.trade_date, o.rule_version,
                       o.grade, o.direction_heat,
                       s.potential_candidates_json::text AS potential_candidates_json,
                       s.competition_candidates_json::text AS competition_candidates_json,
                       s.directions_json::text AS directions_json
                FROM leader_outcome o
                JOIN leader_daily_snapshot s ON s.id = o.snapshot_id
                WHERE o.status = 'PENDING_DATA'
                ORDER BY o.trade_date, o.lane, o.symbol
                """, (rows, rowNumber) -> {
            PotentialLeaderSnapshot.Lane lane = PotentialLeaderSnapshot.Lane.valueOf(rows.getString("lane"));
            List<PotentialLeaderSnapshot.Candidate> candidates = readList(
                    rows.getString(lane == PotentialLeaderSnapshot.Lane.POTENTIAL
                            ? "potential_candidates_json" : "competition_candidates_json"),
                    PotentialLeaderSnapshot.Candidate.class);
            PotentialLeaderSnapshot.Candidate candidate = candidates.stream()
                    .filter(value -> normalizeSymbol(value.symbol()).equals(rows.getString("symbol")))
                    .findFirst().orElse(null);
            List<PotentialLeaderSnapshot.Direction> directions = readList(
                    rows.getString("directions_json"), PotentialLeaderSnapshot.Direction.class);
            PotentialLeaderSnapshot.Direction direction = candidate == null ? null : directions.stream()
                    .filter(value -> value.industry().equals(candidate.industry()))
                    .findFirst().orElse(null);
            return new PendingOutcome(
                    rows.getObject("snapshot_id", UUID.class), lane, rows.getString("symbol"),
                    rows.getObject("trade_date", LocalDate.class), rows.getString("rule_version"),
                    PotentialLeaderSnapshot.Grade.valueOf(rows.getString("grade")),
                    rows.getString("direction_heat"), candidate == null ? null : candidate.industry(),
                    candidate == null ? null : candidate.latestPrice(),
                    direction == null ? List.of() : direction.constituentSymbols());
        });
    }

    public void saveOutcome(OutcomeResult result) {
        jdbc.update("""
                UPDATE leader_outcome
                SET status = ?, success = ?, return_t1 = ?, return_t3 = ?, return_t5 = ?,
                    max_drawdown = ?, closed_limit_up_count = ?, max_board_count = ?,
                    evaluated_through_date = ?, updated_at = now()
                WHERE snapshot_id = ? AND lane = ? AND symbol = ?
                """,
                result.status(), result.success(), result.returnT1(), result.returnT3(), result.returnT5(),
                result.maxDrawdown(), result.closedLimitUpCount(), result.maxBoardCount(),
                result.evaluatedThroughDate(), result.snapshotId(), result.lane().name(),
                normalizeSymbol(result.symbol()));
    }

    public CohortCounts cohortCounts(
            PotentialLeaderSnapshot.Lane lane,
            PotentialLeaderSnapshot.Grade grade,
            String directionHeat
    ) {
        return jdbc.query("""
                SELECT COUNT(*) AS sample_size,
                       COUNT(*) FILTER (WHERE success = TRUE) AS success_count
                FROM leader_outcome
                WHERE lane = ? AND grade = ? AND direction_heat = ?
                  AND status = 'READY' AND success IS NOT NULL
                """, rows -> {
            rows.next();
            return new CohortCounts(rows.getInt("sample_size"), rows.getInt("success_count"));
        }, lane.name(), grade.name(), directionHeat);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("潜力龙头快照序列化失败", exception);
        }
    }

    private <T> List<T> readList(String value, Class<T> elementType) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return List.copyOf(objectMapper.readValue(value, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("潜力龙头快照反序列化失败", exception);
        }
    }

    private String snapshotHash(PotentialLeaderSnapshot snapshot) {
        String canonical = snapshot.ruleVersion() + '|' + snapshot.tradeDate() + '|'
                + json(snapshot.marketSummary()) + '|' + json(snapshot.directions()) + '|'
                + snapshot.potentialCandidates().stream()
                .map(PotentialLeaderSnapshot.Candidate::snapshotHash).sorted().toList() + '|'
                + snapshot.competitionCandidates().stream()
                .map(PotentialLeaderSnapshot.Candidate::snapshotHash).sorted().toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String normalizeSymbol(String symbol) {
        return LeaderDirectionEngine.normalizeSymbol(symbol);
    }

    private record SourceStatus(
            PotentialLeaderSnapshot.Status status,
            PotentialLeaderSnapshot.BoardOptions boardOptions,
            PotentialLeaderSnapshot.Coverage coverage,
            boolean autoRefreshAllowed
    ) {
    }

    public record PendingOutcome(
            UUID snapshotId,
            PotentialLeaderSnapshot.Lane lane,
            String symbol,
            LocalDate tradeDate,
            String ruleVersion,
            PotentialLeaderSnapshot.Grade grade,
            String directionHeat,
            String industry,
            BigDecimal sampleClose,
            List<String> directionConstituents
    ) {
        public PendingOutcome {
            directionConstituents = directionConstituents == null
                    ? List.of() : List.copyOf(directionConstituents);
        }
    }

    public record OutcomeResult(
            UUID snapshotId,
            PotentialLeaderSnapshot.Lane lane,
            String symbol,
            String status,
            Boolean success,
            BigDecimal returnT1,
            BigDecimal returnT3,
            BigDecimal returnT5,
            BigDecimal maxDrawdown,
            Integer closedLimitUpCount,
            Integer maxBoardCount,
            LocalDate evaluatedThroughDate
    ) {
    }

    public record CohortCounts(int sampleSize, int successCount) {
    }
}

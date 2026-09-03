package com.aistock.research.firstboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresFirstBoardBaselineStore implements FirstBoardBaselineStore {

    private final JdbcTemplate jdbc;

    public PostgresFirstBoardBaselineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FirstBoardBaselineRun startRun(
            LocalDate scanTradeDate,
            LocalDate baselineTradeDate,
            String ruleVersion,
            String barDataVersion,
            int universeCount,
            int supportedCount,
            int excludedNameCount,
            int insufficientHistoryCount
    ) {
        FirstBoardBaselineRun run = new FirstBoardBaselineRun(
                UUID.randomUUID(), scanTradeDate, baselineTradeDate, ruleVersion, barDataVersion,
                FirstBoardBaselineRunStatus.BUILDING, universeCount, supportedCount, excludedNameCount,
                insufficientHistoryCount, 0, Instant.now(), null, null);
        jdbc.update("""
                INSERT INTO first_board_baseline_run (
                  run_id, scan_trade_date, baseline_trade_date, rule_version, bar_data_version,
                  status, universe_count, supported_count, excluded_name_count,
                  insufficient_history_count, item_count, started_at, published_at, error_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.runId(), run.scanTradeDate(), run.baselineTradeDate(), run.ruleVersion(),
                run.barDataVersion(), run.status().name(), run.universeCount(), run.supportedCount(),
                run.excludedNameCount(), run.insufficientHistoryCount(), run.itemCount(),
                Timestamp.from(run.startedAt()), null, null);
        return run;
    }

    @Override
    @Transactional
    public FirstBoardBaselineSnapshot publish(
            FirstBoardBaselineRun run, Collection<FirstBoardBaseline> items
    ) {
        Integer building = jdbc.queryForObject("""
                SELECT COUNT(*) FROM first_board_baseline_run
                WHERE run_id = ? AND status = 'BUILDING'
                """, Integer.class, run.runId());
        if (building == null || building != 1) {
            throw new IllegalStateException("首板基线 run 已不处于 BUILDING: " + run.runId());
        }
        List<FirstBoardBaseline> values = items == null ? List.of() : List.copyOf(items);
        if (!values.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO first_board_baseline_item (
                      run_id, symbol, company_name, industry, board, baseline_date, previous_close,
                      ma10, ma20, ma20_slope, rsi14, macd_histogram, technical_pass_count,
                      previous_day_volume_ratio, recent_five_max_volume_ratio, upper_shadow_ratio,
                      three_day_close_range, previous_limit_up
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, values.stream().map(item -> new Object[]{
                    run.runId(), item.symbol(), displayName(item), item.industry(), item.board().name(),
                    item.baselineDate(), item.previousClose(), item.ma10(), item.ma20(), item.ma20Slope(),
                    item.rsi14(), item.macdHistogram(), item.technicalPassCount(),
                    item.previousDayVolumeRatio(), item.recentFiveMaxVolumeRatio(), item.upperShadowRatio(),
                    item.threeDayCloseRange(), item.previousLimitUp()
            }).toList());
        }
        int updated = jdbc.update("""
                UPDATE first_board_baseline_run
                SET status = 'READY', item_count = ?, published_at = now(), error_summary = NULL
                WHERE run_id = ? AND status = 'BUILDING'
                """, values.size(), run.runId());
        if (updated != 1) {
            throw new IllegalStateException("首板基线发布竞争失败: " + run.runId());
        }
        return loadSnapshot(run.runId()).orElseThrow();
    }

    @Override
    public void fail(FirstBoardBaselineRun run, String errorSummary) {
        jdbc.update("""
                UPDATE first_board_baseline_run
                SET status = 'FAILED', error_summary = ?
                WHERE run_id = ? AND status = 'BUILDING'
                """, bounded(errorSummary), run.runId());
    }

    @Override
    public Optional<FirstBoardBaselineSnapshot> findReady(
            LocalDate scanTradeDate,
            LocalDate baselineTradeDate,
            String ruleVersion,
            String barDataVersion
    ) {
        return jdbc.query("""
                SELECT run_id
                FROM first_board_baseline_run
                WHERE scan_trade_date = ?
                  AND baseline_trade_date = ?
                  AND rule_version = ?
                  AND bar_data_version = ?
                  AND status = 'READY'
                ORDER BY published_at DESC
                LIMIT 1
                """, (rows, rowNumber) -> rows.getObject("run_id", UUID.class),
                scanTradeDate, baselineTradeDate, ruleVersion, barDataVersion
        ).stream().findFirst().flatMap(this::loadSnapshot);
    }

    private Optional<FirstBoardBaselineSnapshot> loadSnapshot(UUID runId) {
        Optional<FirstBoardBaselineRun> run = jdbc.query("""
                SELECT * FROM first_board_baseline_run WHERE run_id = ? AND status = 'READY'
                """, this::run, runId).stream().findFirst();
        if (run.isEmpty()) {
            return Optional.empty();
        }
        Map<String, FirstBoardBaseline> items = new LinkedHashMap<>();
        jdbc.query("""
                SELECT * FROM first_board_baseline_item WHERE run_id = ? ORDER BY symbol
                """, rows -> {
            FirstBoardBaseline item = new FirstBoardBaseline(
                    rows.getString("symbol"), rows.getString("company_name"), rows.getString("industry"),
                    FirstBoardBoard.valueOf(rows.getString("board")),
                    rows.getObject("baseline_date", LocalDate.class), rows.getBigDecimal("previous_close"),
                    rows.getBigDecimal("ma10"), rows.getBigDecimal("ma20"), rows.getBigDecimal("ma20_slope"),
                    rows.getBigDecimal("rsi14"), rows.getBigDecimal("macd_histogram"),
                    rows.getInt("technical_pass_count"), rows.getBigDecimal("previous_day_volume_ratio"),
                    rows.getBigDecimal("recent_five_max_volume_ratio"), rows.getBigDecimal("upper_shadow_ratio"),
                    rows.getBigDecimal("three_day_close_range"), rows.getBoolean("previous_limit_up"), List.of());
            items.put(item.symbol(), item);
        }, runId);
        Map<String, List<FirstBoardBar>> recentBars = new LinkedHashMap<>();
        jdbc.query("""
                SELECT symbol, trade_date, open_price, close_price, high_price, low_price, volume
                FROM (
                  SELECT d.symbol, d.trade_date, d.open_price, d.close_price,
                         d.high_price, d.low_price, d.volume,
                         ROW_NUMBER() OVER (
                           PARTITION BY d.symbol ORDER BY d.trade_date DESC
                         ) AS recent_position
                  FROM first_board_daily_bar d
                  JOIN first_board_baseline_item i
                    ON i.run_id = ? AND i.symbol = d.symbol
                  WHERE d.trade_date <= i.baseline_date
                ) recent
                WHERE recent_position <= 20
                ORDER BY symbol, trade_date
                """, rows -> {
            recentBars.computeIfAbsent(rows.getString("symbol"), ignored -> new java.util.ArrayList<>())
                    .add(new FirstBoardBar(
                            rows.getObject("trade_date", LocalDate.class), rows.getBigDecimal("open_price"),
                            rows.getBigDecimal("close_price"), rows.getBigDecimal("high_price"),
                            rows.getBigDecimal("low_price"), rows.getBigDecimal("volume")));
        }, runId);
        items.replaceAll((symbol, item) -> item.withRecentBars(recentBars.getOrDefault(symbol, List.of())));
        FirstBoardBaselineRun metadata = run.get();
        return Optional.of(new FirstBoardBaselineSnapshot(
                metadata.runId(), metadata.scanTradeDate(), metadata.baselineTradeDate(),
                metadata.ruleVersion(), metadata.barDataVersion(), metadata.publishedAt(), items,
                metadata.universeCount(), metadata.supportedCount(), metadata.excludedNameCount(),
                metadata.insufficientHistoryCount()));
    }

    private FirstBoardBaselineRun run(java.sql.ResultSet rows, int rowNumber) throws java.sql.SQLException {
        return new FirstBoardBaselineRun(
                rows.getObject("run_id", UUID.class), rows.getObject("scan_trade_date", LocalDate.class),
                rows.getObject("baseline_trade_date", LocalDate.class), rows.getString("rule_version"),
                rows.getString("bar_data_version"), FirstBoardBaselineRunStatus.valueOf(rows.getString("status")),
                rows.getInt("universe_count"), rows.getInt("supported_count"),
                rows.getInt("excluded_name_count"), rows.getInt("insufficient_history_count"),
                rows.getInt("item_count"), instant(rows.getObject("started_at", OffsetDateTime.class)),
                instant(rows.getObject("published_at", OffsetDateTime.class)), rows.getString("error_summary"));
    }

    private String displayName(FirstBoardBaseline item) {
        return item.name() == null || item.name().isBlank() ? item.symbol() : item.name();
    }

    private String bounded(String value) {
        String message = value == null || value.isBlank() ? "首板基线构建失败" : value;
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

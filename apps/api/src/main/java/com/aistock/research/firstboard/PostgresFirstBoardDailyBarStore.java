package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class PostgresFirstBoardDailyBarStore implements FirstBoardDailyBarStore {

    private static final String UPSERT_BAR_SQL = """
            INSERT INTO first_board_daily_bar (
              symbol, trade_date, open_price, close_price, high_price, low_price,
              volume, amount, turnover_rate, adjust_mode, was_limit_up,
              limit_up_confirmed, source_name, content_hash, observed_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QFQ', FALSE, FALSE, ?, ?, ?, ?)
            ON CONFLICT (symbol, trade_date) DO UPDATE SET
              open_price = EXCLUDED.open_price,
              close_price = EXCLUDED.close_price,
              high_price = EXCLUDED.high_price,
              low_price = EXCLUDED.low_price,
              volume = EXCLUDED.volume,
              amount = EXCLUDED.amount,
              turnover_rate = EXCLUDED.turnover_rate,
              source_name = EXCLUDED.source_name,
              content_hash = EXCLUDED.content_hash,
              observed_at = EXCLUDED.observed_at,
              updated_at = EXCLUDED.updated_at
            WHERE first_board_daily_bar.content_hash <> EXCLUDED.content_hash
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PostgresFirstBoardDailyBarStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    @Override
    public int importLatestHistoryVersions() {
        return jdbc.update("""
                INSERT INTO first_board_daily_bar (
                  symbol, trade_date, open_price, close_price, high_price, low_price,
                  volume, amount, turnover_rate, adjust_mode, was_limit_up,
                  limit_up_confirmed, source_name, content_hash, observed_at, updated_at
                )
                SELECT DISTINCT ON (symbol, trade_date)
                  symbol, trade_date, open_price, close_price, high_price, low_price,
                  volume, amount, turnover_rate, 'QFQ', FALSE, FALSE, source_name,
                  observation_id, observed_at, now()
                FROM market_kline_history
                WHERE bar_type = 'DAY_QFQ'
                  AND open_price IS NOT NULL AND close_price IS NOT NULL
                  AND high_price IS NOT NULL AND low_price IS NOT NULL AND volume IS NOT NULL
                ORDER BY symbol, trade_date, observed_at DESC
                ON CONFLICT (symbol, trade_date) DO UPDATE SET
                  open_price = EXCLUDED.open_price,
                  close_price = EXCLUDED.close_price,
                  high_price = EXCLUDED.high_price,
                  low_price = EXCLUDED.low_price,
                  volume = EXCLUDED.volume,
                  amount = EXCLUDED.amount,
                  turnover_rate = EXCLUDED.turnover_rate,
                  source_name = EXCLUDED.source_name,
                  content_hash = EXCLUDED.content_hash,
                  observed_at = EXCLUDED.observed_at,
                  updated_at = now()
                WHERE first_board_daily_bar.content_hash <> EXCLUDED.content_hash
                """);
    }

    @Override
    @Transactional
    public void upsertBars(List<EastMoneyKLine> rows, String sourceName) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        String source = sourceName == null || sourceName.isBlank() ? "EastMoney" : sourceName;
        List<Object[]> batches = rows.stream()
                .filter(this::valid)
                .map(row -> new Object[]{
                        row.symbol(), Date.valueOf(row.tradeDate()), row.open(), row.close(), row.high(), row.low(),
                        row.volume(), row.amount(), row.turnoverRate(), source, contentHash(row),
                        Timestamp.from(now), Timestamp.from(now)
                })
                .toList();
        if (!batches.isEmpty()) {
            jdbc.batchUpdate(UPSERT_BAR_SQL, batches);
        }
    }

    @Override
    @Transactional
    public void confirmLimitUpPool(LocalDate tradeDate, Set<String> limitUpSymbols) {
        jdbc.update("""
                UPDATE first_board_daily_bar
                SET was_limit_up = FALSE, limit_up_confirmed = TRUE, updated_at = now()
                WHERE trade_date = ?
                """, tradeDate);
        if (limitUpSymbols == null || limitUpSymbols.isEmpty()) {
            return;
        }
        List<Object[]> batches = limitUpSymbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .map(symbol -> new Object[]{tradeDate, symbol})
                .toList();
        jdbc.batchUpdate("""
                UPDATE first_board_daily_bar
                SET was_limit_up = TRUE, limit_up_confirmed = TRUE, updated_at = now()
                WHERE trade_date = ? AND symbol = ?
                """, batches);
    }

    @Override
    public Map<String, Integer> completedBarCounts(
            Collection<String> symbols, LocalDate endDate, int maximumBars
    ) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        var parameters = new MapSqlParameterSource()
                .addValue("symbols", symbols)
                .addValue("endDate", endDate)
                .addValue("maximumBars", maximumBars);
        Map<String, Integer> result = new LinkedHashMap<>();
        namedJdbc.query("""
                SELECT symbol, LEAST(COUNT(*), :maximumBars) AS bar_count
                FROM first_board_daily_bar
                WHERE symbol IN (:symbols) AND trade_date <= :endDate
                GROUP BY symbol
                """, parameters, (RowCallbackHandler) rows ->
                result.put(rows.getString("symbol"), rows.getInt("bar_count")));
        return Map.copyOf(result);
    }

    @Override
    public Map<String, BigDecimal> closingPrices(Collection<String> symbols, LocalDate tradeDate) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        var parameters = new MapSqlParameterSource()
                .addValue("symbols", symbols)
                .addValue("tradeDate", tradeDate);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        namedJdbc.query("""
                SELECT symbol, close_price
                FROM first_board_daily_bar
                WHERE trade_date = :tradeDate AND symbol IN (:symbols) AND adjust_mode = 'QFQ'
                """, parameters, (RowCallbackHandler) rows ->
                result.put(rows.getString("symbol"), rows.getBigDecimal("close_price")));
        return Map.copyOf(result);
    }

    @Override
    public int storedBarCount(LocalDate tradeDate) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM first_board_daily_bar
                WHERE trade_date = ?
                """, Integer.class, tradeDate);
        return count == null ? 0 : count;
    }

    @Override
    public Set<String> confirmedLimitUpSymbols(LocalDate tradeDate) {
        return Set.copyOf(jdbc.query("""
                SELECT symbol
                FROM first_board_daily_bar
                WHERE trade_date = ? AND was_limit_up = TRUE AND limit_up_confirmed = TRUE
                ORDER BY symbol
                """, (rows, rowNumber) -> rows.getString("symbol"), tradeDate));
    }

    @Override
    public List<EastMoneyKLine> recentBars(String symbol, LocalDate endDate, int maximumBars) {
        List<EastMoneyKLine> descending = jdbc.query("""
                SELECT symbol, trade_date, open_price, close_price, high_price, low_price,
                       volume, amount, turnover_rate
                FROM first_board_daily_bar
                WHERE symbol = ? AND trade_date <= ? AND adjust_mode = 'QFQ'
                ORDER BY trade_date DESC
                LIMIT ?
                """, (rows, rowNumber) -> new EastMoneyKLine(
                rows.getString("symbol"), rows.getObject("trade_date", LocalDate.class),
                rows.getBigDecimal("open_price"), rows.getBigDecimal("close_price"),
                rows.getBigDecimal("high_price"), rows.getBigDecimal("low_price"),
                rows.getBigDecimal("volume"), rows.getBigDecimal("amount"),
                rows.getBigDecimal("turnover_rate")
        ), symbol, endDate, maximumBars);
        List<EastMoneyKLine> ascending = new ArrayList<>(descending);
        java.util.Collections.reverse(ascending);
        return List.copyOf(ascending);
    }

    @Override
    public Optional<FirstBoardMarketDay> marketDay(LocalDate tradeDate) {
        return jdbc.query("""
                SELECT * FROM first_board_market_day WHERE trade_date = ?
                """, (RowMapper<FirstBoardMarketDay>) this::mapMarketDay, tradeDate).stream().findFirst();
    }

    @Override
    public Optional<LocalDate> latestReadyMarketDay() {
        return jdbc.query("""
                SELECT trade_date
                FROM first_board_market_day
                WHERE status = 'READY'
                ORDER BY trade_date DESC
                LIMIT 1
                """, (rows, rowNumber) -> rows.getObject(1, LocalDate.class)).stream().findFirst();
    }

    @Override
    public void saveMarketDay(FirstBoardMarketDay marketDay) {
        jdbc.update("""
                INSERT INTO first_board_market_day (
                  trade_date, source_trade_date, status, expected_symbol_count, written_symbol_count,
                  failed_symbol_count, limit_up_pool_complete, bar_data_version, started_at,
                  completed_at, next_retry_at, error_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trade_date) DO UPDATE SET
                  source_trade_date = EXCLUDED.source_trade_date,
                  status = EXCLUDED.status,
                  expected_symbol_count = EXCLUDED.expected_symbol_count,
                  written_symbol_count = EXCLUDED.written_symbol_count,
                  failed_symbol_count = EXCLUDED.failed_symbol_count,
                  limit_up_pool_complete = EXCLUDED.limit_up_pool_complete,
                  bar_data_version = EXCLUDED.bar_data_version,
                  started_at = EXCLUDED.started_at,
                  completed_at = EXCLUDED.completed_at,
                  next_retry_at = EXCLUDED.next_retry_at,
                  error_summary = EXCLUDED.error_summary
                """,
                marketDay.tradeDate(), marketDay.sourceTradeDate(), marketDay.status().name(),
                marketDay.expectedSymbolCount(), marketDay.writtenSymbolCount(), marketDay.failedSymbolCount(),
                marketDay.limitUpPoolComplete(), marketDay.barDataVersion(), timestamp(marketDay.startedAt()),
                timestamp(marketDay.completedAt()), timestamp(marketDay.nextRetryAt()), marketDay.errorSummary());
    }

    @Override
    public String calculateBarDataVersion(LocalDate tradeDate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            jdbc.query("""
                    SELECT symbol, content_hash, was_limit_up, limit_up_confirmed
                    FROM first_board_daily_bar
                    WHERE trade_date = ?
                    ORDER BY symbol
                    """, rows -> {
                updateDigest(digest, rows.getString("symbol"));
                updateDigest(digest, rows.getString("content_hash"));
                updateDigest(digest, Boolean.toString(rows.getBoolean("was_limit_up")));
                updateDigest(digest, Boolean.toString(rows.getBoolean("limit_up_confirmed")));
            }, tradeDate);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("首板日线版本计算失败", exception);
        }
    }

    private FirstBoardMarketDay mapMarketDay(java.sql.ResultSet rows, int rowNumber) throws java.sql.SQLException {
        return new FirstBoardMarketDay(
                rows.getObject("trade_date", LocalDate.class),
                rows.getObject("source_trade_date", LocalDate.class),
                FirstBoardMarketDayStatus.valueOf(rows.getString("status")),
                rows.getInt("expected_symbol_count"), rows.getInt("written_symbol_count"),
                rows.getInt("failed_symbol_count"), rows.getBoolean("limit_up_pool_complete"),
                rows.getString("bar_data_version"), instant(rows.getObject("started_at", OffsetDateTime.class)),
                instant(rows.getObject("completed_at", OffsetDateTime.class)),
                instant(rows.getObject("next_retry_at", OffsetDateTime.class)), rows.getString("error_summary")
        );
    }

    private boolean valid(EastMoneyKLine row) {
        return row != null && row.symbol() != null && !row.symbol().isBlank() && row.tradeDate() != null
                && positive(row.open()) && positive(row.close()) && positive(row.high()) && positive(row.low())
                && row.high().compareTo(row.open()) >= 0 && row.high().compareTo(row.close()) >= 0
                && row.low().compareTo(row.open()) <= 0 && row.low().compareTo(row.close()) <= 0
                && row.volume() != null && row.volume().signum() >= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String contentHash(EastMoneyKLine row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, row.symbol());
            updateDigest(digest, row.tradeDate().toString());
            updateDigest(digest, canonical(row.open()));
            updateDigest(digest, canonical(row.close()));
            updateDigest(digest, canonical(row.high()));
            updateDigest(digest, canonical(row.low()));
            updateDigest(digest, canonical(row.volume()));
            updateDigest(digest, canonical(row.amount()));
            updateDigest(digest, canonical(row.turnoverRate()));
            updateDigest(digest, "QFQ");
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("首板日线摘要计算失败", exception);
        }
    }

    private String canonical(BigDecimal value) {
        return value == null ? "<NULL>" : value.stripTrailingZeros().toPlainString();
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "<NULL>" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

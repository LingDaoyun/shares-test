package com.aistock.research.firstboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

@Service
public class FirstBoardRetentionService {

    static final int BAR_TRADING_DAYS = 120;
    static final int BASELINE_SCAN_DAYS = 5;
    static final int MARKET_DAY_ROWS = 130;

    private final JdbcTemplate jdbc;

    public FirstBoardRetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void cleanup() {
        cutoff("""
                SELECT trade_date
                FROM (SELECT DISTINCT trade_date FROM first_board_daily_bar ORDER BY trade_date DESC) dates
                OFFSET 119 LIMIT 1
                """).ifPresent(date -> jdbc.update(
                "DELETE FROM first_board_daily_bar WHERE trade_date < ?", date));

        cutoff("""
                SELECT scan_trade_date
                FROM (
                  SELECT DISTINCT scan_trade_date
                  FROM first_board_baseline_run
                  WHERE status = 'READY'
                  ORDER BY scan_trade_date DESC
                ) dates
                OFFSET 4 LIMIT 1
                """).ifPresent(date -> jdbc.update("""
                DELETE FROM first_board_baseline_run
                WHERE status <> 'BUILDING' AND scan_trade_date < ?
                """, date));

        cutoff("""
                SELECT trade_date
                FROM (SELECT DISTINCT trade_date FROM first_board_market_day ORDER BY trade_date DESC) dates
                OFFSET 129 LIMIT 1
                """).ifPresent(date -> jdbc.update(
                "DELETE FROM first_board_market_day WHERE trade_date < ?", date));
    }

    private Optional<LocalDate> cutoff(String sql) {
        return jdbc.query(sql, (rows, rowNumber) -> rows.getObject(1, LocalDate.class))
                .stream().findFirst();
    }
}

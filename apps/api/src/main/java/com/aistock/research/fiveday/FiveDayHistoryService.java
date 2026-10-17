package com.aistock.research.fiveday;

import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.aistock.research.fiveday.FiveDayModels.*;

@Service
public class FiveDayHistoryService {
    private static final int HISTORY_SCANS = 30;
    private static final int MAXIMUM_STORED_BARS = 180;
    private static final TypeReference<Map<String, Outcome>> OUTCOMES_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FirstBoardDailyBarStore dailyBarStore;
    private final TradingClockService calendar;
    private final FiveDayOutcomeCalculator calculator;

    public FiveDayHistoryService(JdbcTemplate jdbc, ObjectMapper mapper,
                                FirstBoardDailyBarStore dailyBarStore, TradingClockService calendar) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dailyBarStore = dailyBarStore;
        this.calendar = calendar;
        this.calculator = new FiveDayOutcomeCalculator(calendar);
    }

    /** First complete snapshot wins; a late completion may be saved only as retrospective research. */
    public void capture(Snapshot snapshot) {
        if (snapshot == null || !"READY".equals(snapshot.status()) || snapshot.signalDate() == null
                || snapshot.generatedAt() == null || snapshot.rules() == null || snapshot.coverage() == null
                || (!VERSION.equals(snapshot.ruleVersion()) && !VERSION_V2.equals(snapshot.ruleVersion()))
                || (VERSION_V2.equals(snapshot.ruleVersion()) && snapshot.context() == null)) return;
        Instant started = calendar.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
        String scanId = UUID.randomUUID().toString();
        // Keep the durable snapshot non-forward until the insert has actually completed.
        Snapshot pending = new Snapshot(snapshot.status(), snapshot.ruleVersion(), snapshot.signalDate(),
                snapshot.generatedAt(), false, snapshot.includeGrowth(), snapshot.includeStar(), snapshot.message(),
                snapshot.rules(), snapshot.coverage(), snapshot.candidates(), snapshot.dataGaps(), snapshot.context());
        int inserted = jdbc.update("""
                INSERT INTO five_day_scan
                  (scan_id, signal_date, rule_version, include_growth, include_star,
                   forward_eligible, snapshot_json, outcomes_json, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                ON CONFLICT (signal_date, rule_version, include_growth, include_star) DO NOTHING
                """, scanId, Date.valueOf(pending.signalDate()), pending.ruleVersion(),
                pending.includeGrowth(), pending.includeStar(), false, json(pending), "{}", Timestamp.from(started));
        if (inserted == 0) return;
        Instant completed = calendar.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
        LocalDate entryDate = calendar.verifiedTradingDayAfter(snapshot.signalDate(), 1).orElse(null);
        Instant cutoff = entryDate == null ? null : entryDate.atTime(LocalTime.of(9, 15))
                .atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
        boolean eligible = snapshot.forwardEligible() && cutoff != null && completed.isBefore(cutoff)
                && snapshot.generatedAt().isBefore(cutoff) && !snapshot.generatedAt().isAfter(started);
        List<String> gaps = new ArrayList<>(snapshot.dataGaps());
        if (!eligible) gaps.add("非前瞻样本：生成和保存完成时间必须都早于唯一入场日09:15，且交易日历已核实；不进入前瞻统计。");
        Snapshot frozen = new Snapshot(snapshot.status(), snapshot.ruleVersion(), snapshot.signalDate(),
                snapshot.generatedAt(), eligible, snapshot.includeGrowth(), snapshot.includeStar(), snapshot.message(),
                snapshot.rules(), snapshot.coverage(), snapshot.candidates(), List.copyOf(gaps), snapshot.context());
        jdbc.update("""
                UPDATE five_day_scan SET forward_eligible = ?, snapshot_json = CAST(? AS jsonb), captured_at = ?
                WHERE scan_id = ?
                """, frozen.forwardEligible(), json(frozen), Timestamp.from(completed), scanId);
    }

    public Optional<Snapshot> latest(LocalDate signalDate, boolean includeGrowth, boolean includeStar) {
        return latest(signalDate, includeGrowth, includeStar, VERSION);
    }

    public Optional<Snapshot> latest(LocalDate signalDate, boolean includeGrowth, boolean includeStar, String version) {
        if (signalDate == null) return Optional.empty();
        return jdbc.query("""
                SELECT snapshot_json FROM five_day_scan
                WHERE signal_date = ? AND rule_version = ? AND include_growth = ? AND include_star = ?
                """, (rs, row) -> readSnapshot(rs.getString("snapshot_json")),
                Date.valueOf(signalDate), requireVersion(version), includeGrowth, includeStar).stream().findFirst();
    }

    /** Reads only saved snapshots and results; displaying history never starts market-data work. */
    public History history() { return history(VERSION); }

    public History history(String version) {
        List<Scan> scans = recentScans(version);
        boolean v2 = VERSION_V2.equals(version);
        List<HistoryRow> rows = new ArrayList<>();
        for (Scan scan : scans) {
            for (Candidate candidate : scan.snapshot().candidates()) {
                rows.add(new HistoryRow(scan.id() + ":" + candidate.symbol(), scan.snapshot().signalDate(),
                        scan.capturedAt(), scan.snapshot().forwardEligible(), candidate.symbol(), candidate.name(),
                        candidate.flowDataComplete(), candidate.matchesFundFlow(),
                        scan.outcomes().getOrDefault(candidate.symbol(), awaitingRefresh(candidate)),
                        decision(scan.snapshot(), candidate.symbol())));
            }
        }
        // Deduplicate before selecting the flow-complete subset: later scopes cannot repair an earlier observation.
        Map<String, HistoryRow> firstForward = new LinkedHashMap<>();
        rows.stream().filter(HistoryRow::forwardEligible)
                .sorted(Comparator.comparing(HistoryRow::capturedAt).thenComparing(HistoryRow::observationId))
                .forEach(row -> firstForward.putIfAbsent(row.signalDate() + ":" + row.symbol(), row));
        List<HistoryRow> control = firstForward.values().stream().filter(HistoryRow::flowDataComplete).toList();
        List<HistoryRow> enhanced = control.stream()
                .filter(row -> v2 ? "ELIGIBLE".equals(row.decision()) : row.matchesFundFlow()).toList();
        String controlLabel = v2 ? "主线量价对照（资金完整）" : "量价对照（资金完整）";
        String enhancedLabel = v2 ? "执行条件齐备（对照子集）" : "资金增强（对照的子集）";
        List<String> notes = new ArrayList<>();
        notes.add("窗口为当前版本最近" + HISTORY_SCANS + "个完整成功扫描（含不同板块范围），本次共" + scans.size()
                + "个；不是全历史，也不是组合回测。");
        if (!scans.isEmpty()) {
            LocalDate from = scans.stream().map(scan -> scan.snapshot().signalDate()).min(LocalDate::compareTo).orElseThrow();
            LocalDate to = scans.stream().map(scan -> scan.snapshot().signalDate()).max(LocalDate::compareTo).orElseThrow();
            notes.add("当前保存窗口的信号日期为" + from + "至" + to + "；展示只读取保存结果，未请求行情供应商。");
        }
        notes.add("统计只纳入09:15截止前保存的前瞻样本；同信号日同股票跨板块范围取最早合格观察，重复展示行不重复计数。");
        notes.add(v2
                ? "主线量价对照仅保留资金数据完整者，执行条件齐备组是冻结判断为ELIGIBLE的重叠子集；对照情景可能包含不可执行观察，不代表真实成交。资金缺失者和非前瞻补扫不进入两组均值。"
                : "量价组仅保留资金数据完整者，资金增强组是其满足资金条件的重叠子集；资金缺失者和非前瞻补扫不进入两组均值。");
        notes.add("同股票跨信号日仍可能重复，样本并不独立；子集均值差不能视为因果效果或独立策略组合收益。");
        notes.add("固定净收益和风险退出净收益分别按已完成样本计算；未成交、未退出和缺数据的收益为null，不当成0收益。");
        notes.add(riskCompletionNote(controlLabel, control));
        notes.add(riskCompletionNote(enhancedLabel, enhanced));
        notes.add("所有结果都是已有本地日K上的情景估算，不是实际成交。原计划第5日不能确认卖出时，只有明确后续市场日价格支持才记录延期。");
        return new History(List.copyOf(rows), List.of(cohort(controlLabel, control),
                cohort(enhancedLabel, enhanced)), List.copyOf(notes));
    }

    /** Uses at most 180 locally stored bars per symbol, and does not call a provider or import data. */
    public void refresh() { refresh(VERSION); }

    public void refresh(String version) {
        requireVersion(version);
        var marketNow = calendar.currentMarketDateTime();
        LocalDate asOf = marketNow.toLocalDate();
        if (calendar.isMarketClosedDay(asOf) || marketNow.toLocalTime().isBefore(LocalTime.of(15, 40))) {
            asOf = calendar.previousTradingDay(asOf);
        }
        Map<String, List<com.aistock.research.integration.eastmoney.EastMoneyKLine>> barsBySymbol = new LinkedHashMap<>();
        for (Scan scan : recentScans(version)) {
            Map<String, Outcome> outcomes = new LinkedHashMap<>(scan.outcomes());
            for (Candidate candidate : scan.snapshot().candidates()) {
                Outcome previous = outcomes.get(candidate.symbol());
                if (finished(previous)) continue;
                if (!barsBySymbol.containsKey(candidate.symbol())) {
                    barsBySymbol.put(candidate.symbol(), dailyBarStore.recentBars(candidate.symbol(), asOf, MAXIMUM_STORED_BARS));
                }
                outcomes.put(candidate.symbol(), calculator.evaluate(scan.snapshot(), candidate,
                        barsBySymbol.get(candidate.symbol()), asOf));
            }
            Instant now = calendar.currentMarketDateTime().atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
            jdbc.update("""
                    UPDATE five_day_scan SET outcomes_json = CAST(? AS jsonb), outcomes_as_of = ?, outcomes_updated_at = ?
                    WHERE scan_id = ? AND (outcomes_as_of IS NULL OR outcomes_as_of <= ?)
                    """, json(outcomes), Date.valueOf(asOf), Timestamp.from(now), scan.id(), Date.valueOf(asOf));
        }
    }

    private List<Scan> recentScans(String version) {
        return jdbc.query("""
                SELECT scan_id, snapshot_json, outcomes_json, captured_at FROM five_day_scan
                WHERE rule_version = ? ORDER BY signal_date DESC, captured_at DESC, scan_id DESC LIMIT 30
                """, this::scan, requireVersion(version));
    }

    private String requireVersion(String version) {
        if (!VERSION.equals(version) && !VERSION_V2.equals(version)) {
            throw new IllegalArgumentException("不支持的五日观察规则版本");
        }
        return version;
    }

    private String decision(Snapshot snapshot, String symbol) {
        if (!VERSION_V2.equals(snapshot.ruleVersion())) return null;
        if (snapshot.context() == null) return "UNKNOWN";
        return snapshot.context().assessments().stream()
                .filter(assessment -> symbol.equals(assessment.symbol()))
                .map(assessment -> assessment.decision() == null || assessment.decision().isBlank()
                        ? "UNKNOWN" : assessment.decision())
                .findFirst().orElse("UNKNOWN");
    }

    private Scan scan(ResultSet rs, int row) throws SQLException {
        try {
            String outcomes = rs.getString("outcomes_json");
            return new Scan(rs.getString("scan_id"), readSnapshot(rs.getString("snapshot_json")),
                    outcomes == null || outcomes.isBlank() ? Map.of() : mapper.readValue(outcomes, OUTCOMES_TYPE),
                    rs.getTimestamp("captured_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存的五日结果无法读取", exception);
        }
    }

    private Snapshot readSnapshot(String json) {
        try {
            return mapper.readValue(json, Snapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("保存的五日快照无法读取", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("五日快照或结果无法序列化", exception);
        }
    }

    private Outcome awaitingRefresh(Candidate candidate) {
        return new Outcome("PENDING_REFRESH", "PENDING_REFRESH", candidate.entryDate(), null,
                candidate.plannedExitDate(), null, null, null, null, null, null, null, null,
                List.of("尚未使用本地日K更新日线情景；不是实际成交，收益留空。"));
    }

    private boolean finished(Outcome outcome) {
        return outcome != null && ((complete(outcome.fixedStatus()) && complete(outcome.riskStatus()))
                || ("ENTRY_NOT_FILLED".equals(outcome.fixedStatus()) && "ENTRY_NOT_FILLED".equals(outcome.riskStatus())));
    }

    private boolean complete(String status) { return "COMPLETED".equals(status) || "COMPLETED_DEFERRED".equals(status); }

    private Cohort cohort(String label, List<HistoryRow> rows) {
        List<BigDecimal> fixed = rows.stream().map(row -> row.outcome().fixedNetReturnPercent()).filter(java.util.Objects::nonNull).toList();
        List<BigDecimal> risk = rows.stream().map(row -> row.outcome().riskNetReturnPercent()).filter(java.util.Objects::nonNull).toList();
        int unavailable = (int) rows.stream().filter(row -> unavailable(row.outcome().fixedStatus())).count();
        long profitable = fixed.stream().filter(value -> value.signum() > 0).count();
        BigDecimal winRate = fixed.isEmpty() ? null : BigDecimal.valueOf(profitable).multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(fixed.size()), 4, RoundingMode.HALF_UP);
        return new Cohort(label, rows.size(), fixed.size(), unavailable, average(fixed), winRate, average(risk));
    }

    private boolean unavailable(String status) {
        return "ENTRY_NOT_FILLED".equals(status) || "MISSING_DATA".equals(status) || "CALENDAR_UNAVAILABLE".equals(status);
    }

    private String riskCompletionNote(String label, List<HistoryRow> rows) {
        long completed = rows.stream().filter(row -> row.outcome().riskNetReturnPercent() != null).count();
        return label + "：风险模型完成" + completed + "个，风险净收益均值仅使用这" + completed
                + "个样本；不使用固定模型完成数作分母。";
    }

    private BigDecimal average(List<BigDecimal> values) {
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private record Scan(String id, Snapshot snapshot, Map<String, Outcome> outcomes, Instant capturedAt) { }
}

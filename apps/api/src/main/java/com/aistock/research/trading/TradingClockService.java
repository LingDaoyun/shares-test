package com.aistock.research.trading;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Service
public class TradingClockService {

    public static final ZoneId CHINA_MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime OPEN_CALL_START = LocalTime.of(9, 15);
    public static final LocalTime OPEN_CALL_END = LocalTime.of(9, 25);
    public static final LocalTime MORNING_START = LocalTime.of(9, 30);
    public static final LocalTime MORNING_END = LocalTime.of(11, 30);
    public static final LocalTime AFTERNOON_START = LocalTime.of(13, 0);
    public static final LocalTime CLOSING_CALL_START = LocalTime.of(14, 57);
    public static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 0);
    public static final LocalTime POST_CLOSE_FIXED_PRICE_START = LocalTime.of(15, 5);
    public static final LocalTime POST_CLOSE_FIXED_PRICE_END = LocalTime.of(15, 30);
    public static final LocalTime SHORT_TERM_ENTRY_START = LocalTime.of(14, 45);
    public static final LocalTime SHORT_TERM_ENTRY_END = LocalTime.of(14, 56, 59);
    public static final LocalTime SHORT_TERM_ENTRY_EXCLUSIVE_END = LocalTime.of(14, 57);
    public static final String SHORT_TERM_ENTRY_CHECKPOINT = "TAIL_ENTRY_1445_1456";
    private static final Set<LocalDate> OFFICIAL_2026_HOLIDAYS = Set.of(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"),
            LocalDate.parse("2026-02-16"), LocalDate.parse("2026-02-17"), LocalDate.parse("2026-02-18"),
            LocalDate.parse("2026-02-19"), LocalDate.parse("2026-02-20"), LocalDate.parse("2026-02-23"),
            LocalDate.parse("2026-04-06"),
            LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-04"), LocalDate.parse("2026-05-05"),
            LocalDate.parse("2026-06-19"),
            LocalDate.parse("2026-09-25"),
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-05"),
            LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-07")
    );

    private final Clock clock;

    public TradingClockService() {
        this(Clock.system(CHINA_MARKET_ZONE));
    }

    public TradingClockService(Clock clock) {
        this.clock = clock == null ? Clock.system(CHINA_MARKET_ZONE) : clock;
    }

    public TradingSessionSnapshot currentSession() {
        return classify(LocalDateTime.now(clock.withZone(CHINA_MARKET_ZONE)));
    }

    public boolean isCompletedDailyBar(LocalDate tradeDate) {
        if (tradeDate == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(CHINA_MARKET_ZONE));
        if (tradeDate.isBefore(now.toLocalDate())) {
            return true;
        }
        return tradeDate.equals(now.toLocalDate()) && !now.toLocalTime().isBefore(REGULAR_CLOSE);
    }

    public String shortTermDecisionCheckpoint() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(CHINA_MARKET_ZONE));
        if (!isMarketClosedDay(now.toLocalDate())
                && !now.toLocalTime().isBefore(SHORT_TERM_ENTRY_START)
                && now.toLocalTime().isBefore(SHORT_TERM_ENTRY_EXCLUSIVE_END)) {
            return SHORT_TERM_ENTRY_CHECKPOINT;
        }
        return "NOT_CONFIRMED:" + classify(now).phase();
    }

    public LocalDate nextTradingDay(LocalDate date) {
        LocalDate cursor = date.plusDays(1);
        while (isMarketClosedDay(cursor)) {
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }

    public LocalDate tradingDayAfter(LocalDate date, int offset) {
        LocalDate cursor = date;
        for (int i = 0; i < Math.max(0, offset); i++) {
            cursor = nextTradingDay(cursor);
        }
        return cursor;
    }

    public LocalDate currentMarketDate() {
        return LocalDate.now(clock.withZone(CHINA_MARKET_ZONE));
    }

    public LocalDateTime currentMarketDateTime() {
        return LocalDateTime.now(clock.withZone(CHINA_MARKET_ZONE));
    }

    public TradingSessionSnapshot classify(LocalDateTime dateTime) {
        if (isMarketClosedDay(dateTime.toLocalDate())) {
            boolean weekend = dateTime.getDayOfWeek() == DayOfWeek.SATURDAY || dateTime.getDayOfWeek() == DayOfWeek.SUNDAY;
            return snapshot(
                    "MARKET_CLOSED_DAY",
                    weekend ? "周末休市" : "交易所休市",
                    false,
                    false,
                    false,
                    "休市",
                    List.of("休市日只允许研究、复盘和历史数据更新。"),
                    List.of(weekend ? "当前为周末，不能生成当日盘中执行信号。" : "当前为交易所公告休市日，不能生成当日盘中执行信号。")
            );
        }
        LocalTime time = dateTime.toLocalTime();
        if (!time.isBefore(OPEN_CALL_START) && time.isBefore(OPEN_CALL_END)) {
            return snapshot("OPEN_CALL_AUCTION", "开盘集合竞价", true, false, false, "9:15-9:25",
                    List.of("9:20-9:25 不接受撤单，开盘信号只作观察。"),
                    List.of("集合竞价波动大，不用作短线确认买点。"));
        }
        if (!time.isBefore(MORNING_START) && !time.isAfter(MORNING_END)) {
            return snapshot("MORNING_CONTINUOUS", "上午连续竞价", true, false, false, "9:30-11:30",
                    List.of("上午连续竞价适合更新候选，不做尾盘确认。"),
                    List.of("上午拉升不能替代尾盘承接。"));
        }
        if (!time.isBefore(AFTERNOON_START) && time.isBefore(CLOSING_CALL_START)) {
            return snapshot("AFTERNOON_CONTINUOUS", "下午连续竞价", true, time.isAfter(LocalTime.of(14, 30)), false, "14:45-14:56",
                    List.of("14:30 后进入候选观察，普通股票只在 14:45-14:56 形成可执行尾盘结论。"),
                    List.of("14:45 前只做预选；14:57 起已无法按普通连续竞价的新建议成交。"));
        }
        if (!time.isBefore(CLOSING_CALL_START) && !time.isAfter(REGULAR_CLOSE)) {
            return snapshot("CLOSING_CALL_AUCTION", "收盘集合竞价", true, false, false, "14:57-15:00",
                    List.of("14:57-15:00 数据只用于历史验证和次日研究。"),
                    List.of("14:57-15:00 收盘集合竞价不能新建 14:55 尾盘建议，也不能替代 14:45-14:56 入场窗口。"));
        }
        if (!time.isBefore(POST_CLOSE_FIXED_PRICE_START) && !time.isAfter(POST_CLOSE_FIXED_PRICE_END)) {
            return snapshot("POST_CLOSE_FIXED_PRICE", "盘后固定价格", false, false, true, "15:05-15:30",
                    List.of("盘后固定价格交易以收盘价撮合。"),
                    List.of("15:05-15:30 不能和普通尾盘买点混用，需要单独标注。"));
        }
        if (time.isAfter(REGULAR_CLOSE) && time.isBefore(POST_CLOSE_FIXED_PRICE_START)) {
            return snapshot("REGULAR_CLOSED", "竞价已收盘", false, false, false, "15:00",
                    List.of("普通竞价交易已经收盘。"),
                    List.of("15:00 后新增价格不属于连续竞价尾盘确认。"));
        }
        return snapshot("CLOSED", "非交易时段", false, false, false, "休市",
                List.of("非交易时段只更新研究和复盘，不给盘中执行信号。"),
                List.of("休市行情不能作为当日买点。"));
    }

    public boolean isMarketClosedDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY
                || dayOfWeek == DayOfWeek.SUNDAY
                || OFFICIAL_2026_HOLIDAYS.contains(date);
    }

    private TradingSessionSnapshot snapshot(
            String phase,
            String label,
            boolean regularAuctionOpen,
            boolean closingDecisionWindow,
            boolean postCloseFixedPrice,
            String decisionTimeLabel,
            List<String> rules,
            List<String> warnings
    ) {
        return new TradingSessionSnapshot(
                phase,
                label,
                regularAuctionOpen,
                closingDecisionWindow,
                postCloseFixedPrice,
                decisionTimeLabel,
                rules,
                warnings
        );
    }
}

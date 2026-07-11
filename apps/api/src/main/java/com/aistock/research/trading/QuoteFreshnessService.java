package com.aistock.research.trading;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class QuoteFreshnessService {

    private static final Duration MAX_REALTIME_AGE = Duration.ofMinutes(10);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(2);

    private final TradingClockService tradingClockService;
    private final Clock clock;

    @Autowired
    public QuoteFreshnessService(TradingClockService tradingClockService) {
        this(tradingClockService, Clock.system(TradingClockService.CHINA_MARKET_ZONE));
    }

    public QuoteFreshnessService(TradingClockService tradingClockService, Clock clock) {
        this.tradingClockService = tradingClockService;
        this.clock = clock.withZone(TradingClockService.CHINA_MARKET_ZONE);
    }

    public QuoteFreshnessSnapshot evaluate(EastMoneyQuote quote) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        TradingSessionSnapshot session = tradingClockService.classify(
                LocalDateTime.ofInstant(now, TradingClockService.CHINA_MARKET_ZONE)
        );
        boolean realtimeSession = session.regularAuctionOpen();
        LocalDate tradeDate = quote == null ? null : quote.tradeDate();
        Instant marketTimestamp = quote == null ? null : quote.marketTimestamp();
        Long ageSeconds = marketTimestamp == null ? null : Duration.between(marketTimestamp, now).getSeconds();

        if (!realtimeSession) {
            return snapshot(
                    "MARKET_CLOSED_SNAPSHOT",
                    "休市快照",
                    false,
                    false,
                    tradeDate,
                    marketTimestamp,
                    ageSeconds,
                    marketTimestamp == null
                            ? "当前不是连续竞价时段，行情可用于复盘，但缺少交易所时间戳。"
                            : "当前不是连续竞价时段，展示最近可得的收盘或历史行情快照。"
            );
        }
        if (quote == null || quote.latestPrice() == null) {
            return snapshot("QUOTE_MISSING", "行情缺失", true, true, tradeDate, marketTimestamp, ageSeconds,
                    "交易中缺少有效价格，不能形成执行建议。");
        }
        if (tradeDate == null || marketTimestamp == null) {
            return snapshot("TIMESTAMP_MISSING", "时间戳缺失", true, true, tradeDate, marketTimestamp, ageSeconds,
                    "交易中行情缺少交易日期或市场时间戳，无法证明数据是实时的。");
        }
        if (!today.equals(tradeDate)) {
            return snapshot("STALE_TRADING_DAY", "非当日行情", true, true, tradeDate, marketTimestamp, ageSeconds,
                    "交易中的行情日期不是当前交易日，已阻断短线执行建议。");
        }
        if (marketTimestamp.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            return snapshot("TIMESTAMP_INVALID", "时间戳异常", true, true, tradeDate, marketTimestamp, ageSeconds,
                    "市场时间戳明显晚于系统时间，需要校准数据源或服务器时钟。");
        }
        if (Duration.between(marketTimestamp, now).compareTo(MAX_REALTIME_AGE) > 0) {
            return snapshot("STALE", "行情过期", true, true, tradeDate, marketTimestamp, ageSeconds,
                    "交易中行情已超过 10 分钟未更新，已阻断短线执行建议。");
        }
        return snapshot("FRESH", "实时有效", true, false, tradeDate, marketTimestamp, Math.max(ageSeconds, 0L),
                "行情日期和市场时间戳均通过实时性校验。");
    }

    private QuoteFreshnessSnapshot snapshot(
            String status,
            String label,
            boolean realtimeSession,
            boolean blocked,
            LocalDate tradeDate,
            Instant marketTimestamp,
            Long ageSeconds,
            String reason
    ) {
        return new QuoteFreshnessSnapshot(
                status,
                label,
                realtimeSession,
                blocked,
                tradeDate,
                marketTimestamp,
                ageSeconds,
                reason
        );
    }
}

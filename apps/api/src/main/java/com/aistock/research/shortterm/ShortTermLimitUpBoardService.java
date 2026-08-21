package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyBrokenBoardPoolEntry;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIndexVolumeBar;
import com.aistock.research.integration.eastmoney.EastMoneyLimitDownPoolEntry;
import com.aistock.research.integration.eastmoney.EastMoneyLimitUpPoolEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 涨停看板服务：拉取东方财富涨停池/炸板池/跌停池，聚合行业分布、连板梯队、
 * 炸板率与封板时间分布，并对外提供市场情绪判定的真实涨停池脉冲。
 *
 * <p>涨停池是主数据，获取失败时快照整体不可用；炸板池/跌停池独立容错，
 * 失败只标记数据缺口，不影响其余指标。盘中结果按 60 秒快照缓存，刷新即重取。
 */
@Service
public class ShortTermLimitUpBoardService {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermLimitUpBoardService.class);
    private static final ZoneId CHINA_MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long SNAPSHOT_TTL_SECONDS = 60;
    private static final LocalTime EARLY_SEAL_BOUNDARY = LocalTime.of(10, 0);
    private static final LocalTime MORNING_SEAL_BOUNDARY = LocalTime.of(11, 30);
    private static final LocalTime AFTERNOON_SEAL_BOUNDARY = LocalTime.of(14, 30);
    private static final BigDecimal HEAVY_BREAK_RATIO = new BigDecimal("40");
    private static final BigDecimal LIGHT_BREAK_RATIO = new BigDecimal("15");
    private static final BigDecimal MODERATE_BREAK_RATIO = new BigDecimal("25");
    private static final BigDecimal EARLY_DOMINANT_SHARE = new BigDecimal("50");
    private static final String UNCLASSIFIED_INDUSTRY = "未分类";

    private final EastMoneyClient eastMoneyClient;
    private final Map<LocalDate, CachedSnapshot> cache = new ConcurrentHashMap<>();

    private record CachedSnapshot(ShortTermLimitUpBoardSnapshot snapshot, Instant expiresAt) {
    }

    public ShortTermLimitUpBoardService(EastMoneyClient eastMoneyClient) {
        this.eastMoneyClient = eastMoneyClient;
    }

    public ShortTermLimitUpBoardSnapshot snapshot(LocalDate date) {
        LocalDate tradeDate = date == null ? LocalDate.now(CHINA_MARKET_ZONE) : date;
        Instant now = Instant.now();
        CachedSnapshot cached = cache.get(tradeDate);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.snapshot();
        }
        ShortTermLimitUpBoardSnapshot snapshot = fetchSnapshot(tradeDate);
        cache.put(tradeDate, new CachedSnapshot(snapshot, now.plusSeconds(SNAPSHOT_TTL_SECONDS)));
        return snapshot;
    }

    /**
     * 供市场状态与情绪判定使用的当日涨停池脉冲；任何失败都返回空，
     * 调用方回落到原有的涨跌幅近似推断。
     */
    public Optional<ShortTermLimitUpSentiment> currentPulse() {
        try {
            ShortTermLimitUpBoardSnapshot snapshot = snapshot(null);
            return snapshot.available() ? Optional.ofNullable(snapshot.sentiment()) : Optional.empty();
        } catch (Exception exception) {
            logger.warn("涨停池情绪脉冲不可用，市场状态判定回落到涨跌幅近似：{}", rootMessage(exception));
            return Optional.empty();
        }
    }

    private ShortTermLimitUpBoardSnapshot fetchSnapshot(LocalDate tradeDate) {
        Instant fetchedAt = Instant.now();
        List<EastMoneyLimitUpPoolEntry> limitUpEntries;
        try {
            limitUpEntries = eastMoneyClient.fetchLimitUpPool(tradeDate);
        } catch (Exception exception) {
            logger.warn("涨停池获取失败：{}", rootMessage(exception));
            return ShortTermLimitUpBoardSnapshot.unavailable(
                    tradeDate,
                    fetchedAt,
                    "东方财富涨停池获取失败：" + rootMessage(exception)
            );
        }
        if (limitUpEntries.isEmpty()) {
            return ShortTermLimitUpBoardSnapshot.unavailable(
                    tradeDate,
                    fetchedAt,
                    "当日涨停池为空（可能是非交易日、盘前或数据源尚未生成）"
            );
        }
        List<String> dataGaps = new ArrayList<>();
        List<EastMoneyBrokenBoardPoolEntry> brokenEntries = List.of();
        try {
            brokenEntries = eastMoneyClient.fetchBrokenBoardPool(tradeDate);
        } catch (Exception exception) {
            logger.warn("炸板池获取失败：{}", rootMessage(exception));
            dataGaps.add("炸板池获取失败，炸板率缺失：" + rootMessage(exception));
        }
        List<EastMoneyLimitDownPoolEntry> limitDownEntries = List.of();
        try {
            limitDownEntries = eastMoneyClient.fetchLimitDownPool(tradeDate);
        } catch (Exception exception) {
            logger.warn("跌停池获取失败：{}", rootMessage(exception));
            dataGaps.add("跌停池获取失败，跌停家数缺失：" + rootMessage(exception));
        }
        ShortTermMarketTurnover marketTurnover = null;
        try {
            marketTurnover = fetchMarketTurnover(tradeDate);
        } catch (Exception exception) {
            logger.warn("大盘量能对比获取失败：{}", rootMessage(exception));
            dataGaps.add("大盘量能对比获取失败：" + rootMessage(exception));
        }
        return buildSnapshot(tradeDate, fetchedAt, limitUpEntries, brokenEntries, limitDownEntries, marketTurnover, dataGaps);
    }

    private ShortTermMarketTurnover fetchMarketTurnover(LocalDate tradeDate) {
        List<EastMoneyIndexVolumeBar> shanghaiBars = eastMoneyClient.fetchIndexDailyVolumeBars("sh000001", 2);
        List<EastMoneyIndexVolumeBar> shenzhenBars = eastMoneyClient.fetchIndexDailyVolumeBars("sz399106", 2);
        BigDecimal todayAmountYuan = null;
        try {
            todayAmountYuan = eastMoneyClient.fetchShSzIndexTodayAmount();
        } catch (Exception exception) {
            logger.warn("指数今日成交额获取失败，仅展示量能对比：{}", rootMessage(exception));
        }
        return marketTurnover(tradeDate, shanghaiBars, shenzhenBars, todayAmountYuan);
    }

    /**
     * 两市指数各自取目标日的量与前一根的量合计，做增量/缩量对比；
     * 目标日取两市最新根中较早的一天，保证两侧行情日期对齐。
     */
    static ShortTermMarketTurnover marketTurnover(
            LocalDate tradeDate,
            List<EastMoneyIndexVolumeBar> shanghaiBars,
            List<EastMoneyIndexVolumeBar> shenzhenBars,
            BigDecimal todayAmountYuan
    ) {
        LocalDate effectiveDate = latestAlignedDate(shanghaiBars, shenzhenBars);
        BigDecimal shanghaiToday = volumeOn(shanghaiBars, effectiveDate);
        BigDecimal shenzhenToday = volumeOn(shenzhenBars, effectiveDate);
        BigDecimal shanghaiPrevious = volumeBefore(shanghaiBars, effectiveDate);
        BigDecimal shenzhenPrevious = volumeBefore(shenzhenBars, effectiveDate);
        if (shanghaiToday == null || shenzhenToday == null || shanghaiPrevious == null || shenzhenPrevious == null) {
            throw new IllegalStateException("指数量能数据不完整，无法对比：" + effectiveDate);
        }
        BigDecimal todayVolume = shanghaiToday.add(shenzhenToday);
        BigDecimal previousVolume = shanghaiPrevious.add(shenzhenPrevious);
        if (todayVolume.signum() <= 0 || previousVolume.signum() <= 0) {
            throw new IllegalStateException("指数量能数据不完整，无法对比：" + effectiveDate);
        }
        BigDecimal changePercent = todayVolume.subtract(previousVolume)
                .multiply(BigDecimal.valueOf(100))
                .divide(previousVolume, 2, RoundingMode.HALF_UP);
        String label = turnoverLabel(changePercent);
        StringBuilder explanation = new StringBuilder("上证指数+深证综指成交量 ")
                .append(effectiveDate)
                .append(" 合计 ")
                .append(todayVolume.toBigInteger())
                .append(" 手，前一日 ")
                .append(previousVolume.toBigInteger())
                .append(" 手，变化 ")
                .append(changePercent.stripTrailingZeros().toPlainString())
                .append("%（")
                .append(label)
                .append("）。口径：按两市成交量（手）对比，±3% 内为平量，±10% 外为显著。");
        if (todayAmountYuan != null && todayAmountYuan.signum() > 0) {
            explanation.append("今日两市成交额约 ")
                    .append(todayAmountYuan.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP)
                            .toBigInteger())
                    .append(" 亿（实时快照）。");
        }
        return new ShortTermMarketTurnover(
                effectiveDate,
                todayVolume,
                previousVolume,
                changePercent,
                todayAmountYuan,
                label,
                explanation.toString()
        );
    }

    static String turnoverLabel(BigDecimal changePercent) {
        if (changePercent.compareTo(new BigDecimal("10")) >= 0) {
            return "显著增量";
        }
        if (changePercent.compareTo(new BigDecimal("3")) >= 0) {
            return "温和增量";
        }
        if (changePercent.compareTo(new BigDecimal("-3")) > 0) {
            return "平量";
        }
        if (changePercent.compareTo(new BigDecimal("-10")) > 0) {
            return "温和缩量";
        }
        return "显著缩量";
    }

    private static LocalDate latestAlignedDate(
            List<EastMoneyIndexVolumeBar> shanghaiBars,
            List<EastMoneyIndexVolumeBar> shenzhenBars
    ) {
        if (shanghaiBars == null || shanghaiBars.isEmpty() || shenzhenBars == null || shenzhenBars.isEmpty()) {
            throw new IllegalStateException("指数日K量能数据为空");
        }
        LocalDate shanghaiLatest = shanghaiBars.get(shanghaiBars.size() - 1).tradeDate();
        LocalDate shenzhenLatest = shenzhenBars.get(shenzhenBars.size() - 1).tradeDate();
        return shanghaiLatest.isBefore(shenzhenLatest) ? shanghaiLatest : shenzhenLatest;
    }

    /** 返回 null 表示该日没有量能行，调用方视为数据缺口。 */
    private static BigDecimal volumeOn(List<EastMoneyIndexVolumeBar> bars, LocalDate date) {
        return bars.stream()
                .filter(bar -> date.equals(bar.tradeDate()))
                .map(EastMoneyIndexVolumeBar::volumeHands)
                .findFirst()
                .orElse(null);
    }

    /** 返回 null 表示该日之前没有量能行。 */
    private static BigDecimal volumeBefore(List<EastMoneyIndexVolumeBar> bars, LocalDate date) {
        BigDecimal volume = null;
        for (EastMoneyIndexVolumeBar bar : bars) {
            if (bar.tradeDate().isBefore(date)) {
                volume = bar.volumeHands();
            }
        }
        return volume;
    }

    ShortTermLimitUpBoardSnapshot buildSnapshot(
            LocalDate tradeDate,
            Instant fetchedAt,
            List<EastMoneyLimitUpPoolEntry> limitUpEntries,
            List<EastMoneyBrokenBoardPoolEntry> brokenEntries,
            List<EastMoneyLimitDownPoolEntry> limitDownEntries,
            ShortTermMarketTurnover marketTurnover,
            List<String> dataGaps
    ) {
        List<ShortTermLimitUpStock> stocks = limitUpEntries.stream()
                .sorted(boardDepthThenSealTime())
                .map(ShortTermLimitUpBoardService::toStock)
                .toList();
        return new ShortTermLimitUpBoardSnapshot(
                tradeDate,
                fetchedAt,
                true,
                null,
                stocks,
                industryStats(limitUpEntries),
                sentiment(limitUpEntries, brokenEntries.size(), limitDownEntries.size()),
                marketTurnover,
                dataGaps
        );
    }

    private static Comparator<EastMoneyLimitUpPoolEntry> boardDepthThenSealTime() {
        return Comparator
                .comparingInt(EastMoneyLimitUpPoolEntry::consecutiveBoards).reversed()
                .thenComparing(
                        entry -> entry.firstSealTime(),
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
    }

    private static ShortTermLimitUpStock toStock(EastMoneyLimitUpPoolEntry entry) {
        return new ShortTermLimitUpStock(
                entry.symbol(),
                entry.name(),
                entry.industry(),
                entry.latestPrice(),
                entry.changePercent(),
                entry.amount(),
                entry.turnoverRate(),
                entry.consecutiveBoards(),
                entry.statDays(),
                entry.statBoards(),
                entry.sealFunds(),
                entry.firstSealTime(),
                entry.lastSealTime(),
                entry.sealBreakCount()
        );
    }

    static List<ShortTermLimitUpIndustryStat> industryStats(List<EastMoneyLimitUpPoolEntry> entries) {
        Map<String, List<EastMoneyLimitUpPoolEntry>> byIndustry = new LinkedHashMap<>();
        for (EastMoneyLimitUpPoolEntry entry : entries) {
            String industry = entry.industry() == null || entry.industry().isBlank()
                    ? UNCLASSIFIED_INDUSTRY
                    : entry.industry();
            byIndustry.computeIfAbsent(industry, key -> new ArrayList<>()).add(entry);
        }
        return byIndustry.values().stream()
                .map(group -> new ShortTermLimitUpIndustryStat(
                        group.get(0).industry() == null || group.get(0).industry().isBlank()
                                ? UNCLASSIFIED_INDUSTRY
                                : group.get(0).industry(),
                        group.size(),
                        group.stream().mapToInt(EastMoneyLimitUpPoolEntry::consecutiveBoards).max().orElse(0),
                        group.stream()
                                .map(EastMoneyLimitUpPoolEntry::amount)
                                .filter(Objects::nonNull)
                                .reduce(BigDecimal.ZERO, BigDecimal::add),
                        group.stream()
                                .sorted(boardDepthThenSealTime())
                                .map(EastMoneyLimitUpPoolEntry::name)
                                .limit(3)
                                .toList()
                ))
                .sorted(Comparator
                        .comparingInt(ShortTermLimitUpIndustryStat::limitUpCount).reversed()
                        .thenComparing(Comparator
                                .comparingInt(ShortTermLimitUpIndustryStat::maxConsecutiveBoards).reversed()))
                .toList();
    }

    static ShortTermLimitUpSentiment sentiment(
            List<EastMoneyLimitUpPoolEntry> limitUpEntries,
            Integer brokenCount,
            Integer limitDownCount
    ) {
        int limitUpCount = limitUpEntries.size();
        int maxConsecutiveBoards = limitUpEntries.stream()
                .mapToInt(EastMoneyLimitUpPoolEntry::consecutiveBoards)
                .max()
                .orElse(0);
        int boards2PlusCount = (int) limitUpEntries.stream()
                .filter(entry -> entry.consecutiveBoards() >= 2)
                .count();
        int boards3PlusCount = (int) limitUpEntries.stream()
                .filter(entry -> entry.consecutiveBoards() >= 3)
                .count();
        int sealedBeforeTenCount = 0;
        int sealedMorningCount = 0;
        int sealedAfternoonCount = 0;
        int sealedTailCount = 0;
        for (EastMoneyLimitUpPoolEntry entry : limitUpEntries) {
            LocalTime sealedAt = entry.firstSealTime();
            if (sealedAt == null) {
                continue;
            }
            if (sealedAt.isBefore(EARLY_SEAL_BOUNDARY)) {
                sealedBeforeTenCount++;
            } else if (sealedAt.isBefore(MORNING_SEAL_BOUNDARY)) {
                sealedMorningCount++;
            } else if (sealedAt.isBefore(AFTERNOON_SEAL_BOUNDARY)) {
                sealedAfternoonCount++;
            } else {
                sealedTailCount++;
            }
        }
        BigDecimal sealBreakRatioPercent = brokenCount == null || limitUpCount + brokenCount <= 0
                ? null
                : percent(brokenCount, limitUpCount + brokenCount);
        BigDecimal earlySealSharePercent = limitUpCount == 0
                ? null
                : percent(sealedBeforeTenCount, limitUpCount);
        String tone = tone(limitUpCount, sealBreakRatioPercent, earlySealSharePercent);
        return new ShortTermLimitUpSentiment(
                limitUpCount,
                brokenCount,
                limitDownCount,
                sealBreakRatioPercent,
                maxConsecutiveBoards,
                boards2PlusCount,
                boards3PlusCount,
                sealedBeforeTenCount,
                sealedMorningCount,
                sealedAfternoonCount,
                sealedTailCount,
                earlySealSharePercent,
                tone,
                explanation(limitUpCount, brokenCount, limitDownCount, sealBreakRatioPercent,
                        maxConsecutiveBoards, boards2PlusCount, earlySealSharePercent, tone)
        );
    }

    private static String tone(int limitUpCount, BigDecimal sealBreakRatioPercent, BigDecimal earlySealSharePercent) {
        boolean heavyBreak = sealBreakRatioPercent != null
                && sealBreakRatioPercent.compareTo(HEAVY_BREAK_RATIO) >= 0;
        boolean lightBreak = sealBreakRatioPercent == null
                || sealBreakRatioPercent.compareTo(LIGHT_BREAK_RATIO) <= 0;
        boolean moderateBreak = sealBreakRatioPercent == null
                || sealBreakRatioPercent.compareTo(MODERATE_BREAK_RATIO) <= 0;
        boolean earlyDominant = earlySealSharePercent != null
                && earlySealSharePercent.compareTo(EARLY_DOMINANT_SHARE) >= 0;
        if (heavyBreak) {
            return "接力退潮";
        }
        if (limitUpCount < 25) {
            return "情绪冰点";
        }
        if (limitUpCount >= 60 && lightBreak && earlyDominant) {
            return "情绪强势";
        }
        if (limitUpCount >= 40 && moderateBreak) {
            return "情绪偏暖";
        }
        return "中性震荡";
    }

    private static String explanation(
            int limitUpCount,
            Integer brokenCount,
            Integer limitDownCount,
            BigDecimal sealBreakRatioPercent,
            int maxConsecutiveBoards,
            int boards2PlusCount,
            BigDecimal earlySealSharePercent,
            String tone
    ) {
        StringBuilder text = new StringBuilder("涨停 ")
                .append(limitUpCount)
                .append(" 家");
        if (brokenCount != null) {
            text.append("，炸板 ").append(brokenCount).append(" 家");
            if (sealBreakRatioPercent != null) {
                text.append("（炸板率 ").append(sealBreakRatioPercent).append("%）");
            }
        }
        if (limitDownCount != null) {
            text.append("，跌停 ").append(limitDownCount).append(" 家");
        }
        text.append("，最高 ").append(maxConsecutiveBoards).append(" 连板，2 板以上 ")
                .append(boards2PlusCount).append(" 家");
        if (earlySealSharePercent != null) {
            text.append("，10 点前封板占比 ").append(earlySealSharePercent).append("%");
        }
        text.append("；口径：").append(tone);
        text.append("（阈值：炸板率≥40% 判退潮，涨停<25 家判冰点，"
                + "涨停≥60 家且炸板率≤15% 且早盘封板过半判强势，涨停≥40 家且炸板率≤25% 判偏暖）。");
        return text.toString();
    }

    private static BigDecimal percent(long part, long total) {
        return BigDecimal.valueOf(part * 100L)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        return message == null || message.isBlank() ? "未知错误" : message;
    }
}

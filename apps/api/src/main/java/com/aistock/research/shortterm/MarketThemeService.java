package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyConceptClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit provider concepts; market statistics never infer membership from stock names. */
@Service
public class MarketThemeService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SOURCE = "东方财富概念板块";
    private static final Set<String> STRUCTURAL_BOARDS = Set.of(
            "BK0596", "BK0707", "BK0804", "BK0611", "BK0612", "BK0701", "BK0821",
            "BK0815", "BK0816", "BK0817", "BK1630", "BK1631", "BK0535", "BK0536", "BK0520", "BK0552",
            "BK0705", "BK0638", "BK0743", "BK0867", "BK1050", "BK1051", "BK1632", "BK1633", "BK1645");
    private static final Comparator<BigDecimal> DESCENDING = Comparator.nullsLast(Comparator.reverseOrder());

    private final EastMoneyConceptClient source;
    private final ShortTermLimitUpBoardService limitUps;
    private final Clock clock;
    private MarketThemeSnapshot cached;
    private Instant expiresAt = Instant.MIN;

    @Autowired
    public MarketThemeService(EastMoneyConceptClient source, ShortTermLimitUpBoardService limitUps) {
        this(source, limitUps, Clock.systemUTC());
    }

    MarketThemeService(EastMoneyConceptClient source, ShortTermLimitUpBoardService limitUps, Clock clock) {
        this.source = source;
        this.limitUps = limitUps;
        this.clock = clock;
    }

    /** One bounded bulk fetch per cache interval, shared by concurrent requests. */
    public synchronized MarketThemeSnapshot snapshot() {
        if (cached != null && clock.instant().isBefore(expiresAt)) return cached;
        try {
            cached = buildSnapshot(source.fetchBoards(), clock.instant());
            expiresAt = clock.instant().plusSeconds(60);
        } catch (RuntimeException failure) {
            String gap = "概念板块行情获取失败，暂时无法更新市场题材。";
            if (cached != null && cached.validQuoteCount() > 0) {
                List<String> gaps = new ArrayList<>(cached.dataGaps());
                if (!gaps.contains(gap)) gaps.add(gap);
                cached = new MarketThemeSnapshot(cached.tradeDate(), cached.fetchedAt(), "STALE", SOURCE,
                        cached.totalConceptCount(), cached.excludedStructuralCount(), cached.validQuoteCount(), cached.themes(), gaps);
            } else {
                cached = new MarketThemeSnapshot(null, clock.instant(), "UNAVAILABLE", SOURCE, 0, 0, 0,
                        List.of(), List.of(gap));
            }
            expiresAt = clock.instant().plusSeconds(10);
        }
        return cached;
    }

    private MarketThemeSnapshot buildSnapshot(EastMoneyConceptClient.BoardSnapshot raw, Instant now) {
        List<EastMoneyConceptClient.Board> concepts = raw.boards().stream()
                .filter(board -> !STRUCTURAL_BOARDS.contains(board.code())).toList();
        LocalDate date = concepts.stream().map(EastMoneyConceptClient.Board::marketTimestamp)
                .filter(time -> validTime(time, now)).map(time -> time.atZone(MARKET_ZONE).toLocalDate())
                .max(Comparator.naturalOrder()).orElse(null);
        List<MarketTheme> themes = new ArrayList<>();
        int valid = 0;
        int incomplete = 0;
        for (EastMoneyConceptClient.Board board : concepts) {
            boolean dated = sameDate(board.marketTimestamp(), date, now);
            BigDecimal change = dated ? board.changePercent() : null;
            BigDecimal amount = dated ? nonNegative(board.amount()) : null;
            Integer up = dated ? nonNegative(board.advancing()) : null;
            Integer down = dated ? nonNegative(board.declining()) : null;
            Integer flat = dated ? nonNegative(board.unchanged()) : null;
            if (change != null) valid++;
            if (change == null || amount == null || up == null || down == null || flat == null) incomplete++;
            themes.add(new MarketTheme(board.code(), board.name(), change, amount, up, down, flat,
                    ratio(up, down, flat), validTime(board.marketTimestamp(), now) ? board.marketTimestamp() : null));
        }
        themes.sort(Comparator.comparing(MarketTheme::changePercent, DESCENDING)
                .thenComparing(MarketTheme::advancingRatioPercent, DESCENDING)
                .thenComparing(MarketTheme::amount, DESCENDING).thenComparing(MarketTheme::code));
        List<String> gaps = new ArrayList<>();
        if (date == null) gaps.add("概念行情缺少可核验的来源时间，无法确定行情日期。");
        else if (!date.equals(now.atZone(MARKET_ZONE).toLocalDate())) {
            gaps.add("来源行情日期为 " + date + "，不是今日行情；本页未将历史数据重新标为今日。");
        }
        if (incomplete > 0) gaps.add(incomplete + " 个题材的行情字段或来源时间不完整，缺失、跨日或未来时间的报价显示为未知。");
        if (concepts.isEmpty()) gaps.add("来源没有返回可展示的市场题材。");
        String status = valid == 0 ? "UNAVAILABLE" : gaps.isEmpty() ? "READY" : "PARTIAL";
        return new MarketThemeSnapshot(date, raw.fetchedAt(), status, SOURCE, raw.boards().size(),
                raw.boards().size() - concepts.size(), valid, themes, gaps);
    }

    public MarketThemeDetail detail(String code, LocalDate requestedDate) {
        MarketThemeSnapshot catalog = snapshot();
        MarketTheme theme = catalog.themes().stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前题材目录中不存在该概念，请刷新看板后重试。"));
        if (requestedDate == null || catalog.tradeDate() == null || !requestedDate.equals(catalog.tradeDate())) {
            throw new IllegalArgumentException("题材行情日期已变化或无法确认，请刷新看板后重试。");
        }
        List<String> gaps = new ArrayList<>();
        if ("STALE".equals(catalog.status())) gaps.add("题材列表更新失败，当前题材指标来自保留的旧快照。");
        EastMoneyConceptClient.MemberSnapshot membership;
        try {
            membership = source.fetchMembers(code);
            long unique = membership.members().stream().map(EastMoneyConceptClient.Member::symbol)
                    .filter(Objects::nonNull).distinct().count();
            if (membership.total() != membership.members().size() || unique != membership.total()) {
                throw new IllegalStateException("成分覆盖不完整");
            }
        } catch (RuntimeException failure) {
            gaps.add("题材成分获取不完整，无法确认成分范围、涨停家数和最高连板。");
            return new MarketThemeDetail(requestedDate, clock.instant(), theme, SOURCE, false,
                    null, null, null, null, List.of(), List.of(), gaps);
        }
        Instant now = clock.instant();
        List<MarketThemeDetail.Member> members = membership.members().stream().map(member -> {
            boolean dated = sameDate(member.marketTimestamp(), requestedDate, now);
            return new MarketThemeDetail.Member(member.symbol(), member.name(),
                    dated && member.latestPrice() != null && member.latestPrice().signum() > 0 ? member.latestPrice() : null,
                    dated ? member.changePercent() : null, dated ? nonNegative(member.amount()) : null,
                    dated ? nonNegative(member.turnoverRate()) : null,
                    validTime(member.marketTimestamp(), now) ? member.marketTimestamp() : null);
        }).sorted(Comparator.comparing(MarketThemeDetail.Member::changePercent, DESCENDING)
                .thenComparing(MarketThemeDetail.Member::symbol)).toList();
        long missingQuotes = members.stream().filter(member -> member.latestPrice() == null || member.changePercent() == null
                || member.amount() == null || member.turnoverRate() == null).count();
        if (missingQuotes > 0) gaps.add(missingQuotes + " 只成分行情缺失或与题材日期不一致，相关报价显示为未知。");
        if (!requestedDate.equals(now.atZone(MARKET_ZONE).toLocalDate())) {
            gaps.add("成分为当前归属，缺少 " + requestedDate + " 历史成分记录，无法确认该日题材涨停统计。");
            return new MarketThemeDetail(requestedDate, membership.fetchedAt(), theme, SOURCE, true,
                    membership.total(), null, null, null, List.of(), members, gaps);
        }
        Set<String> symbols = members.stream().map(MarketThemeDetail.Member::symbol).collect(Collectors.toSet());
        List<ShortTermLimitUpStock> sealed = List.of();
        Integer sealedCount = null;
        Integer maxBoards = null;
        Instant limitUpFetchedAt = null;
        try {
            ShortTermLimitUpBoardSnapshot pool = limitUps.cachedSnapshot(requestedDate);
            if (pool == null || !pool.available() || !requestedDate.equals(pool.tradeDate())) {
                gaps.add("同日涨停池未就绪或已过期，请刷新涨停看板后重试；涨停家数和最高连板暂时未知。");
            } else {
                Map<String, ShortTermLimitUpStock> unique = new LinkedHashMap<>();
                pool.stocks().stream().filter(stock -> symbols.contains(stock.symbol()))
                        .forEach(stock -> unique.putIfAbsent(stock.symbol(), stock));
                sealed = unique.values().stream().sorted(Comparator.comparingInt(ShortTermLimitUpStock::consecutiveBoards)
                        .reversed().thenComparing(ShortTermLimitUpStock::symbol)).toList();
                sealedCount = sealed.size();
                maxBoards = sealed.stream().mapToInt(ShortTermLimitUpStock::consecutiveBoards).max().orElse(0);
                limitUpFetchedAt = pool.fetchedAt();
            }
        } catch (RuntimeException failure) {
            gaps.add("同日涨停池获取失败，涨停家数和最高连板暂时未知。");
        }
        return new MarketThemeDetail(requestedDate, membership.fetchedAt(), theme, SOURCE, true,
                membership.total(), sealedCount, maxBoards, limitUpFetchedAt, sealed, members, gaps);
    }

    private static boolean validTime(Instant time, Instant now) {
        return time != null && time.isAfter(Instant.EPOCH) && !time.isAfter(now);
    }

    private static boolean sameDate(Instant time, LocalDate date, Instant now) {
        return date != null && validTime(time, now) && date.equals(time.atZone(MARKET_ZONE).toLocalDate());
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0 ? value : null;
    }

    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    private static BigDecimal ratio(Integer up, Integer down, Integer flat) {
        if (up == null || down == null || flat == null) return null;
        long total = (long) up + down + flat;
        return total == 0 ? null : BigDecimal.valueOf(up).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}

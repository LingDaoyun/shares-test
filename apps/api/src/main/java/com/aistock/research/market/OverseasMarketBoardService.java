package com.aistock.research.market;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyGlobalQuote;
import com.aistock.research.integration.yahoo.YahooFinanceClient;
import com.aistock.research.integration.yahoo.YahooSparkQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 外围市场看板服务：美股/日本/韩国的指数与行业涨跌。
 *
 * <p>口径说明：美股行业用 11 只 SPDR 板块 ETF，日韩行业用当地行业 ETF 代理
 * （日本 TOPIX-17 系列、韩国 KODEX 系列），涨跌幅即 ETF 最新收盘相对前收。
 * 指数与美股行业走东财（全球 secid 体系）；日韩行业走 Yahoo Finance，
 * 需国际网络，失败时对应市场块降级、不影响美股块。
 */
@Service
public class OverseasMarketBoardService {

    private static final Logger logger = LoggerFactory.getLogger(OverseasMarketBoardService.class);
    private static final long SNAPSHOT_TTL_SECONDS = 300;

    private static final ZoneId US_ZONE = ZoneId.of("America/New_York");
    private static final ZoneId JP_ZONE = ZoneId.of("Asia/Tokyo");
    private static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");

    /** 行情引用：代码 + 固定中文名 + 计价币种（东财全球行情不带币种）。 */
    private record QuoteRef(String code, String name, String currency) {
    }

    private static final List<QuoteRef> US_INDEX_REFS = List.of(
            new QuoteRef("100.DJIA", "道琼斯", "USD"),
            new QuoteRef("100.SPX", "标普500", "USD"),
            new QuoteRef("100.NDX", "纳斯达克", "USD")
    );
    private static final List<QuoteRef> JP_INDEX_REFS = List.of(
            new QuoteRef("100.N225", "日经225", "JPY")
    );
    private static final List<QuoteRef> KR_INDEX_REFS = List.of(
            new QuoteRef("100.KS11", "韩国KOSPI", "KRW")
    );
    private static final List<QuoteRef> GLOBAL_STRIP_REFS = List.of(
            new QuoteRef("100.HSI", "恒生指数", "HKD"),
            new QuoteRef("100.GDAXI", "德国DAX", "EUR"),
            new QuoteRef("100.FTSE", "英国富时100", "GBP"),
            new QuoteRef("100.SX5E", "欧洲斯托克50", "EUR"),
            new QuoteRef("100.FCHI", "法国CAC40", "EUR")
    );
    /** 美股行业 = SPDR Select Sector ETF（东财 107. 前缀）。 */
    private static final List<QuoteRef> US_SECTOR_REFS = List.of(
            new QuoteRef("107.XLK", "信息技术", "USD"),
            new QuoteRef("107.XLV", "医疗保健", "USD"),
            new QuoteRef("107.XLI", "工业", "USD"),
            new QuoteRef("107.XLE", "能源", "USD"),
            new QuoteRef("107.XLF", "金融", "USD"),
            new QuoteRef("107.XLY", "可选消费", "USD"),
            new QuoteRef("107.XLP", "必需消费", "USD"),
            new QuoteRef("107.XLB", "原材料", "USD"),
            new QuoteRef("107.XLRE", "房地产", "USD"),
            new QuoteRef("107.XLU", "公用事业", "USD"),
            new QuoteRef("107.XLC", "通信服务", "USD")
    );
    /** 日本行业 = NEXT FUNDS TOPIX-17 系列（东证上市，Yahoo 代码 .T）。 */
    private static final List<QuoteRef> JP_SECTOR_REFS = List.of(
            new QuoteRef("1617.T", "食品·农林水产", "JPY"),
            new QuoteRef("1618.T", "能源·资源", "JPY"),
            new QuoteRef("1619.T", "建设·资材", "JPY"),
            new QuoteRef("1620.T", "原材料·化学", "JPY"),
            new QuoteRef("1621.T", "医药", "JPY"),
            new QuoteRef("1622.T", "汽车·运输设备", "JPY"),
            new QuoteRef("1623.T", "钢铁·有色", "JPY"),
            new QuoteRef("1624.T", "机械", "JPY"),
            new QuoteRef("1625.T", "电气·精密", "JPY"),
            new QuoteRef("1626.T", "信息通信·服务", "JPY"),
            new QuoteRef("1627.T", "电力·燃气", "JPY"),
            new QuoteRef("1628.T", "运输·物流", "JPY"),
            new QuoteRef("1629.T", "商社·批发", "JPY"),
            new QuoteRef("1630.T", "零售", "JPY"),
            new QuoteRef("1631.T", "银行", "JPY"),
            new QuoteRef("1632.T", "非银金融", "JPY"),
            new QuoteRef("1633.T", "房地产", "JPY")
    );
    /** 韩国行业 = KODEX 行业系列（KRX 上市，Yahoo 代码 .KS）。 */
    private static final List<QuoteRef> KR_SECTOR_REFS = List.of(
            new QuoteRef("091160.KS", "半导体", "KRW"),
            new QuoteRef("091170.KS", "银行", "KRW"),
            new QuoteRef("091180.KS", "汽车", "KRW"),
            new QuoteRef("102970.KS", "证券", "KRW"),
            new QuoteRef("117460.KS", "能源化学", "KRW"),
            new QuoteRef("117680.KS", "钢铁", "KRW"),
            new QuoteRef("117700.KS", "建设", "KRW"),
            new QuoteRef("102960.KS", "造船", "KRW"),
            new QuoteRef("140710.KS", "运输", "KRW"),
            new QuoteRef("266360.KS", "传媒内容", "KRW"),
            new QuoteRef("266370.KS", "IT硬件", "KRW"),
            new QuoteRef("300950.KS", "游戏", "KRW"),
            new QuoteRef("305720.KS", "二次电池", "KRW")
    );
    /** 东财没有的日韩指数，走 Yahoo；数据源不认识时自动缺席（Yahoo 无 TOPIX，仅补 KOSDAQ）。 */
    private static final QuoteRef KR_KOSDAQ_REF = new QuoteRef("^KQ11", "KOSDAQ", "KRW");

    private static final Comparator<OverseasIndexQuote> BY_CHANGE_DESC = Comparator
            .comparing(OverseasIndexQuote::changePercent, Comparator.nullsLast(Comparator.reverseOrder()));

    private final EastMoneyClient eastMoneyClient;
    private final YahooFinanceClient yahooFinanceClient;
    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>();

    private record CachedSnapshot(OverseasMarketBoardSnapshot snapshot, Instant expiresAt) {
    }

    public OverseasMarketBoardService(EastMoneyClient eastMoneyClient, YahooFinanceClient yahooFinanceClient) {
        this.eastMoneyClient = eastMoneyClient;
        this.yahooFinanceClient = yahooFinanceClient;
    }

    public OverseasMarketBoardSnapshot snapshot() {
        CachedSnapshot cached = cache.get();
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.snapshot();
        }
        OverseasMarketBoardSnapshot snapshot = fetchSnapshot();
        // 不可用快照多半是瞬时网络问题，不进缓存，下次请求立即重试
        if (snapshot.available()) {
            cache.set(new CachedSnapshot(snapshot, now.plusSeconds(SNAPSHOT_TTL_SECONDS)));
        }
        return snapshot;
    }

    OverseasMarketBoardSnapshot fetchSnapshot() {
        Instant generatedAt = Instant.now();
        List<String> dataGaps = new ArrayList<>();
        Map<String, EastMoneyGlobalQuote> eastQuotes;
        try {
            eastQuotes = fetchEastQuotes(generatedAt);
        } catch (RuntimeException exception) {
            return OverseasMarketBoardSnapshot.unavailable(generatedAt, exception.getMessage());
        }
        Map<String, YahooSparkQuote> yahooQuotes = fetchYahooQuotesFailSoft(dataGaps);

        List<OverseasIndexQuote> strip = globalStrip(eastQuotes, dataGaps);
        List<OverseasMarketSection> markets = List.of(
                usSection(eastQuotes, generatedAt, dataGaps),
                jpSection(eastQuotes, yahooQuotes, generatedAt, dataGaps),
                krSection(eastQuotes, yahooQuotes, generatedAt, dataGaps)
        );
        return new OverseasMarketBoardSnapshot(generatedAt, true, null, strip, markets, dataGaps);
    }

    private Map<String, EastMoneyGlobalQuote> fetchEastQuotes(Instant generatedAt) {
        List<String> secids = Stream.of(US_INDEX_REFS, JP_INDEX_REFS, KR_INDEX_REFS, GLOBAL_STRIP_REFS, US_SECTOR_REFS)
                .flatMap(List::stream)
                .map(QuoteRef::code)
                .toList();
        try {
            return eastMoneyClient.fetchGlobalQuotes(secids).stream()
                    .collect(Collectors.toMap(EastMoneyGlobalQuote::code, quote -> quote, (left, right) -> left));
        } catch (RuntimeException exception) {
            logger.warn("外围看板东财全球行情获取失败：{}", rootMessage(exception));
            throw new IllegalStateException("东方财富全球行情获取失败，外围看板暂不可用：" + rootMessage(exception), exception);
        }
    }

    private Map<String, YahooSparkQuote> fetchYahooQuotesFailSoft(List<String> dataGaps) {
        List<String> symbols = Stream.of(JP_SECTOR_REFS, KR_SECTOR_REFS)
                .flatMap(List::stream)
                .map(QuoteRef::code)
                .collect(Collectors.toList());
        symbols.add(KR_KOSDAQ_REF.code());
        try {
            return yahooFinanceClient.fetchSparkQuotes(symbols);
        } catch (RuntimeException exception) {
            logger.warn("外围看板 Yahoo 行情获取失败：{}", rootMessage(exception));
            dataGaps.add("Yahoo Finance 不可达，日本/韩国行业数据缺失（需国际网络）：" + rootMessage(exception));
            return Map.of();
        }
    }

    private List<OverseasIndexQuote> globalStrip(Map<String, EastMoneyGlobalQuote> quotes, List<String> dataGaps) {
        List<OverseasIndexQuote> strip = GLOBAL_STRIP_REFS.stream()
                .map(ref -> eastQuote(ref, quotes))
                .flatMap(Optional::stream)
                .toList();
        if (strip.size() < GLOBAL_STRIP_REFS.size()) {
            dataGaps.add("全球指数速览缺少 " + (GLOBAL_STRIP_REFS.size() - strip.size()) + " 条行情。");
        }
        return strip;
    }

    private OverseasMarketSection usSection(
            Map<String, EastMoneyGlobalQuote> quotes,
            Instant generatedAt,
            List<String> dataGaps
    ) {
        List<OverseasIndexQuote> industries = US_SECTOR_REFS.stream()
                .map(ref -> eastQuote(ref, quotes))
                .flatMap(Optional::stream)
                .sorted(BY_CHANGE_DESC)
                .toList();
        String unavailableReason = null;
        if (industries.isEmpty()) {
            unavailableReason = "美股行业 ETF 行情缺失，行业涨跌暂不可用。";
            dataGaps.add(unavailableReason);
        } else if (industries.size() < US_SECTOR_REFS.size()) {
            dataGaps.add("美股行业缺少 " + (US_SECTOR_REFS.size() - industries.size()) + " 个板块行情。");
        }
        return new OverseasMarketSection(
                "US",
                "美股",
                marketStatus(US_ZONE, LocalTime.of(9, 30), LocalTime.of(16, 0), generatedAt),
                indexQuotes(US_INDEX_REFS, quotes, generatedAt),
                industries,
                unavailableReason
        );
    }

    private OverseasMarketSection jpSection(
            Map<String, EastMoneyGlobalQuote> eastQuotes,
            Map<String, YahooSparkQuote> yahooQuotes,
            Instant generatedAt,
            List<String> dataGaps
    ) {
        List<OverseasIndexQuote> indices = new ArrayList<>(indexQuotes(JP_INDEX_REFS, eastQuotes, generatedAt));
        List<OverseasIndexQuote> industries = JP_SECTOR_REFS.stream()
                .map(ref -> yahooQuote(ref, yahooQuotes))
                .flatMap(Optional::stream)
                .sorted(BY_CHANGE_DESC)
                .toList();
        return sectionWithYahooIndustries(
                "JP", "日本", JP_ZONE, LocalTime.of(9, 0), LocalTime.of(15, 0),
                indices, industries, JP_SECTOR_REFS.size(), "日本", generatedAt, dataGaps
        );
    }

    private OverseasMarketSection krSection(
            Map<String, EastMoneyGlobalQuote> eastQuotes,
            Map<String, YahooSparkQuote> yahooQuotes,
            Instant generatedAt,
            List<String> dataGaps
    ) {
        List<OverseasIndexQuote> indices = new ArrayList<>(indexQuotes(KR_INDEX_REFS, eastQuotes, generatedAt));
        yahooQuote(KR_KOSDAQ_REF, yahooQuotes).ifPresent(indices::add);
        List<OverseasIndexQuote> industries = KR_SECTOR_REFS.stream()
                .map(ref -> yahooQuote(ref, yahooQuotes))
                .flatMap(Optional::stream)
                .sorted(BY_CHANGE_DESC)
                .toList();
        return sectionWithYahooIndustries(
                "KR", "韩国", KR_ZONE, LocalTime.of(9, 0), LocalTime.of(15, 30),
                indices, industries, KR_SECTOR_REFS.size(), "韩国", generatedAt, dataGaps
        );
    }

    private OverseasMarketSection sectionWithYahooIndustries(
            String market,
            String marketLabel,
            ZoneId zone,
            LocalTime open,
            LocalTime close,
            List<OverseasIndexQuote> indices,
            List<OverseasIndexQuote> industries,
            int expectedCount,
            String labelForReason,
            Instant generatedAt,
            List<String> dataGaps
    ) {
        String unavailableReason = null;
        if (industries.isEmpty()) {
            unavailableReason = "Yahoo 行情不可达，" + labelForReason + "行业数据暂缺（需国际网络）。";
            if (!dataGaps.contains(unavailableReason)) {
                dataGaps.add(unavailableReason);
            }
        } else if (industries.size() < expectedCount) {
            dataGaps.add(labelForReason + "行业缺少 " + (expectedCount - industries.size()) + " 个板块行情。");
        }
        return new OverseasMarketSection(
                market,
                marketLabel,
                marketStatus(zone, open, close, generatedAt),
                indices,
                industries,
                unavailableReason
        );
    }

    private List<OverseasIndexQuote> indexQuotes(
            List<QuoteRef> refs,
            Map<String, EastMoneyGlobalQuote> quotes,
            Instant generatedAt
    ) {
        return refs.stream()
                .map(ref -> eastQuote(ref, quotes))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<OverseasIndexQuote> eastQuote(QuoteRef ref, Map<String, EastMoneyGlobalQuote> quotes) {
        // 引用代码是 secid（100.DJIA），东财返回的 f12 只有代码部分（DJIA）
        EastMoneyGlobalQuote quote = quotes.get(codeOf(ref.code()));
        if (quote == null) {
            return Optional.empty();
        }
        return Optional.of(new OverseasIndexQuote(
                quote.code(),
                ref.name(),
                quote.latestPrice(),
                quote.changePercent(),
                quote.changeValue(),
                ref.currency(),
                quote.quoteTime()
        ));
    }

    private Optional<OverseasIndexQuote> yahooQuote(QuoteRef ref, Map<String, YahooSparkQuote> quotes) {
        YahooSparkQuote quote = quotes.get(ref.code());
        if (quote == null) {
            return Optional.empty();
        }
        return Optional.of(new OverseasIndexQuote(
                quote.symbol(),
                ref.name(),
                quote.lastClose(),
                quote.changePercent(),
                null,
                ref.currency(),
                quote.lastTradeTime()
        ));
    }

    /** 100.DJIA -> DJIA；无前缀的（Yahoo 代码）原样返回。 */
    private static String codeOf(String secid) {
        int dot = secid.indexOf('.');
        return dot >= 0 ? secid.substring(dot + 1) : secid;
    }

    /** 按交易所当地时段判断开闭市；周末判休市。时段为近似值，仅作展示提示。 */
    static String marketStatus(ZoneId zone, LocalTime open, LocalTime close, Instant now) {
        return marketStatus(ZonedDateTime.ofInstant(now, zone), open, close);
    }

    static String marketStatus(ZonedDateTime localNow, LocalTime open, LocalTime close) {
        DayOfWeek day = localNow.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return "CLOSED";
        }
        LocalTime time = localNow.toLocalTime();
        return !time.isBefore(open) && time.isBefore(close) ? "OPEN" : "CLOSED";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}

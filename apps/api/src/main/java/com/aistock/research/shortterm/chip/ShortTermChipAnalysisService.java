package com.aistock.research.shortterm.chip;

import com.aistock.research.configuration.ShortTermChipSettings;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.tushare.TushareChipClient;
import com.aistock.research.integration.tushare.TushareChipFetchResult;
import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ShortTermChipAnalysisService {

    private final ShortTermChipSettings settings;
    private final TushareChipClient tushareChipClient;
    private final ShortTermChipVerificationStore verificationStore;
    private final TradingClockService tradingClockService;

    public ShortTermChipAnalysisService(
            ShortTermChipSettings settings,
            TushareChipClient tushareChipClient,
            ShortTermChipVerificationStore verificationStore,
            TradingClockService tradingClockService
    ) {
        this.settings = settings;
        this.tushareChipClient = tushareChipClient;
        this.verificationStore = verificationStore;
        this.tradingClockService = tradingClockService;
    }

    public ShortTermChipSnapshot analyze(
            EastMoneyQuote quote,
            List<EastMoneyKLine> input,
            boolean allowExternalFetch,
            Instant dataCutoffAt
    ) {
        if (!settings.enabled()) {
            return null;
        }
        try {
            return analyzeSafely(quote, input, allowExternalFetch, dataCutoffAt);
        } catch (RuntimeException exception) {
            LocalDate tradeDate = quote == null ? null : quote.tradeDate();
            LocalChipDistribution insufficient = LocalChipDistribution.insufficient(
                    ChipCalculationMode.COMPLETED_BAR,
                    tradeDate,
                    0,
                    BigDecimal.ZERO,
                    List.of("筹码结构计算失败: " + exception.getClass().getSimpleName())
            );
            ChipVerificationResult verification = new ChipVerificationResult(
                    ChipVerificationStatus.INSUFFICIENT,
                    BigDecimal.ZERO,
                    null, null, null,
                    List.of("筹码模型未参与排序")
            );
            return scorer().score(insufficient, verification, null);
        }
    }

    private ShortTermChipSnapshot analyzeSafely(
            EastMoneyQuote quote,
            List<EastMoneyKLine> input,
            boolean allowExternalFetch,
            Instant dataCutoffAt
    ) {
        if (quote == null || quote.symbol() == null) {
            throw new IllegalArgumentException("筹码分析缺少股票行情");
        }
        List<EastMoneyKLine> sorted = input == null ? List.of() : input.stream()
                .filter(bar -> bar != null && bar.tradeDate() != null)
                .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
                .toList();
        List<EastMoneyKLine> completed = sorted.stream()
                .filter(bar -> tradingClockService.isCompletedDailyBar(bar.tradeDate()))
                .toList();
        LocalChipDistributionCalculator calculator = calculator();
        BigDecimal completedPrice = completed.isEmpty() ? quote.latestPrice() : completed.get(completed.size() - 1).close();
        LocalChipDistribution completedLocal = calculator.calculate(
                completed, completedPrice, ChipCalculationMode.COMPLETED_BAR);
        LocalDate expectedTradeDate = completedLocal.tradeDate();

        List<String> operationGaps = new ArrayList<>();
        Optional<ShortTermChipVerificationEvidence> cached = safeFind(
                quote.symbol(), expectedTradeDate, operationGaps);
        ExternalChipPerformance external = cached.map(ShortTermChipVerificationEvidence::external).orElse(null);
        String externalError = cached.map(ShortTermChipVerificationEvidence::errorSummary).orElse(null);
        String configuredToken = settings.tushareToken();
        boolean refreshExternal = allowExternalFetch
                && settings.tushareEnabled()
                && configuredToken != null
                && !configuredToken.isBlank()
                && (cached.isEmpty()
                || cached.get().snapshot().verificationStatus() == ChipVerificationStatus.SINGLE_SOURCE
                || cached.get().snapshot().verificationStatus() == ChipVerificationStatus.STALE);
        if (refreshExternal && expectedTradeDate != null) {
            TushareChipFetchResult fetchResult = tushareChipClient.fetchPerformance(
                    quote.symbol(), expectedTradeDate);
            external = fetchResult.value().orElse(null);
            externalError = ChipEvidenceSanitizer.sanitize(fetchResult.errorSummary());
            if (externalError != null) {
                operationGaps.add(externalError);
            }
        }

        ChipVerificationResult verification = verifier().verify(completedLocal, external, expectedTradeDate);
        ShortTermChipSnapshot completedSnapshot = scorer().score(completedLocal, verification, external)
                .withAdditionalGaps(operationGaps);
        completedSnapshot = safeSave(
                quote.symbol(), expectedTradeDate, completedSnapshot, external,
                dataCutoffAt, externalError);

        List<EastMoneyKLine> incomplete = sorted.stream()
                .filter(bar -> !tradingClockService.isCompletedDailyBar(bar.tradeDate()))
                .toList();
        if (incomplete.isEmpty()) {
            return completedSnapshot;
        }
        List<EastMoneyKLine> intradayBars = new ArrayList<>(completed);
        intradayBars.add(incomplete.get(incomplete.size() - 1));
        LocalChipDistribution intraday = calculator.calculate(
                intradayBars,
                quote.latestPrice(),
                ChipCalculationMode.INTRADAY_ESTIMATE
        );
        return scorer().score(intraday, verification, external)
                .withAdditionalGaps(operationGaps);
    }

    private Optional<ShortTermChipVerificationEvidence> safeFind(
            String symbol,
            LocalDate tradeDate,
            List<String> gaps
    ) {
        if (tradeDate == null) {
            return Optional.empty();
        }
        try {
            return verificationStore.find(symbol, tradeDate, ShortTermChipSnapshot.MODEL_VERSION);
        } catch (RuntimeException exception) {
            gaps.add("筹码认证缓存读取失败");
            return Optional.empty();
        }
    }

    private ShortTermChipSnapshot safeSave(
            String symbol,
            LocalDate tradeDate,
            ShortTermChipSnapshot snapshot,
            ExternalChipPerformance external,
            Instant dataCutoffAt,
            String externalError
    ) {
        if (tradeDate == null) {
            return snapshot;
        }
        try {
            verificationStore.save(
                    symbol,
                    tradeDate,
                    ShortTermChipSnapshot.MODEL_VERSION,
                    snapshot,
                    external,
                    dataCutoffAt,
                    Instant.now(),
                    externalError
            );
            return snapshot;
        } catch (RuntimeException exception) {
            return snapshot.withAdditionalGaps(List.of("筹码认证缓存写入失败"));
        }
    }

    private LocalChipDistributionCalculator calculator() {
        return new LocalChipDistributionCalculator(
                settings.lookbackBars(),
                settings.priceBuckets(),
                settings.minValidBars(),
                settings.minTurnoverCoverage()
        );
    }

    private ChipModelVerifier verifier() {
        return new ChipModelVerifier(
                settings.maxAverageCostDeviation(),
                settings.minCostBandOverlap(),
                settings.maxWinnerRateDeviation(),
                settings.singleSourceCoefficient()
        );
    }

    private ChipStructureScorer scorer() {
        return new ChipStructureScorer(settings.rankingWeight());
    }
}

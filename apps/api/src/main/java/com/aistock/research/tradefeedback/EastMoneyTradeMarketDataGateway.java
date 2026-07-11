package com.aistock.research.tradefeedback;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class EastMoneyTradeMarketDataGateway implements TradeMarketDataGateway {

    static final String EAST_MONEY_LIVE_QUOTE = "EAST_MONEY_LIVE_QUOTE";
    static final String TENCENT_LIVE_QUOTE_FALLBACK = "TENCENT_LIVE_QUOTE_FALLBACK";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final EastMoneyClient eastMoneyClient;

    public EastMoneyTradeMarketDataGateway(EastMoneyClient eastMoneyClient) {
        this.eastMoneyClient = eastMoneyClient;
    }

    @Override
    public List<MarketBar> dailyKLines(String symbol, LocalDate begin, LocalDate end) {
        return eastMoneyClient.fetchDailyKLines(symbol, begin, end).stream()
                .map(row -> new MarketBar(row.tradeDate(), row.close(), row.high(), row.low()))
                .toList();
    }

    @Override
    public Optional<LatestMarketPrice> latestPrice(String symbol) {
        RuntimeException eastMoneyFailure = null;
        try {
            Optional<EastMoneyQuote> quote = validQuote(
                    symbol, eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of(symbol), 1));
            if (quote.isPresent()) {
                return Optional.of(latestPrice(quote.get(), EAST_MONEY_LIVE_QUOTE));
            }
        } catch (RuntimeException exception) {
            eastMoneyFailure = exception;
        }

        try {
            Optional<LatestMarketPrice> fallback = validQuote(
                    symbol, eastMoneyClient.fetchTencentQuotes(List.of(symbol), 1))
                    .map(quote -> latestPrice(quote, TENCENT_LIVE_QUOTE_FALLBACK));
            if (fallback.isPresent()) {
                return fallback;
            }
            if (eastMoneyFailure != null) {
                throw eastMoneyFailure;
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            if (eastMoneyFailure != null && exception != eastMoneyFailure) {
                exception.addSuppressed(eastMoneyFailure);
            }
            throw exception;
        }
    }

    private Optional<EastMoneyQuote> validQuote(String symbol, List<EastMoneyQuote> quotes) {
        return quotes.stream()
                .filter(quote -> symbol.equals(quote.symbol()))
                .filter(quote -> quote.latestPrice() != null
                        && quote.latestPrice().signum() > 0
                        && quote.tradeDate() != null
                        && quote.marketTimestamp() != null
                        && quote.tradeDate().equals(quote.marketTimestamp().atZone(SHANGHAI).toLocalDate()))
                .findFirst();
    }

    private LatestMarketPrice latestPrice(EastMoneyQuote quote, String source) {
        return new LatestMarketPrice(
                quote.latestPrice(), source, quote.tradeDate(), quote.marketTimestamp());
    }
}

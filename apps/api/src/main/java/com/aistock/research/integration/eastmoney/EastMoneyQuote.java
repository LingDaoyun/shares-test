package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record EastMoneyQuote(
        String symbol,
        String name,
        String market,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal turnoverRate,
        BigDecimal volume,
        BigDecimal amount,
        BigDecimal peRatio,
        BigDecimal pbRatio,
        BigDecimal peTtm,
        String sourceName,
        String quoteUrl,
        Instant fetchedAt,
        LocalDate tradeDate,
        Instant marketTimestamp,
        BigDecimal totalMarketValue,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice
) {
    public EastMoneyQuote(
            String symbol, String name, String market, String industry,
            BigDecimal latestPrice, BigDecimal changePercent, BigDecimal turnoverRate,
            BigDecimal volume, BigDecimal amount, BigDecimal peRatio, BigDecimal pbRatio,
            BigDecimal peTtm, String sourceName, String quoteUrl, Instant fetchedAt,
            LocalDate tradeDate, Instant marketTimestamp, BigDecimal totalMarketValue
    ) {
        this(symbol, name, market, industry, latestPrice, changePercent, turnoverRate,
                volume, amount, peRatio, pbRatio, peTtm, sourceName, quoteUrl,
                fetchedAt, tradeDate, marketTimestamp, totalMarketValue,
                null, null, null);
    }

    public EastMoneyQuote(
            String symbol, String name, String market, String industry,
            BigDecimal latestPrice, BigDecimal changePercent, BigDecimal turnoverRate,
            BigDecimal volume, BigDecimal amount, BigDecimal peRatio, BigDecimal pbRatio,
            BigDecimal peTtm, String sourceName, String quoteUrl, Instant fetchedAt,
            LocalDate tradeDate, Instant marketTimestamp
    ) {
        this(symbol, name, market, industry, latestPrice, changePercent, turnoverRate,
                volume, amount, peRatio, pbRatio, peTtm, sourceName, quoteUrl,
                fetchedAt, tradeDate, marketTimestamp, null,
                null, null, null);
    }

    public EastMoneyQuote(
            String symbol,
            String name,
            String market,
            String industry,
            BigDecimal latestPrice,
            BigDecimal changePercent,
            BigDecimal turnoverRate,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal peRatio,
            BigDecimal pbRatio,
            BigDecimal peTtm,
            String sourceName,
            String quoteUrl,
            Instant fetchedAt
    ) {
        this(
                symbol,
                name,
                market,
                industry,
                latestPrice,
                changePercent,
                turnoverRate,
                volume,
                amount,
                peRatio,
                pbRatio,
                peTtm,
                sourceName,
                quoteUrl,
                fetchedAt,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}

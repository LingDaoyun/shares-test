package com.aistock.research.longterm;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.valuation.ValuationContext;

import java.math.BigDecimal;
import java.util.List;

public record LongTermAssessmentInput(
        String symbol,
        String industry,
        BigDecimal latestPrice,
        ValuationContext valuationContext,
        EastMoneyAnnualIndicator latestAnnualIndicator,
        List<EastMoneyAnnualIndicator> annualHistory,
        BigDecimal industryRankPercentile,
        int industrySampleCount,
        boolean industryRankRevenueBased,
        boolean assetAdvantagedIndustry,
        List<String> sourceDataGaps
) {
    public LongTermAssessmentInput(
            String symbol,
            String industry,
            BigDecimal latestPrice,
            ValuationContext valuationContext,
            EastMoneyAnnualIndicator latestAnnualIndicator,
            List<EastMoneyAnnualIndicator> annualHistory,
            BigDecimal industryRankPercentile,
            int industrySampleCount,
            boolean industryRankRevenueBased,
            boolean assetAdvantagedIndustry
    ) {
        this(
                symbol,
                industry,
                latestPrice,
                valuationContext,
                latestAnnualIndicator,
                annualHistory,
                industryRankPercentile,
                industrySampleCount,
                industryRankRevenueBased,
                assetAdvantagedIndustry,
                List.of()
        );
    }
}

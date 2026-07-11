package com.aistock.research.company;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CompanyProfile(
        String symbol,
        String name,
        String market,
        String industry,
        String themeCode,
        BigDecimal themeRelevance,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal peTtm,
        BigDecimal pbRatio,
        BigDecimal turnoverRate,
        BigDecimal amount,
        String quoteUrl,
        String dataSource,
        String fetchedAt,
        String financialReportDate,
        String financialDataType,
        boolean liveData,
        List<String> coreAssets,
        List<String> risks,
        Map<String, BigDecimal> factors,
        List<EvidenceItem> evidence
) {
}

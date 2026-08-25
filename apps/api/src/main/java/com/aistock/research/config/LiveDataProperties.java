package com.aistock.research.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "research.live-data")
public record LiveDataProperties(
        String eastmoneyFundFlowUrl,
        String eastmoneyFundFlowMinuteUrl,
        int stockLimit,
        String eastmoneyQuoteUrl,
        String eastmoneyFinancialUrl,
        String cninfoAnnouncementUrl,
        String govPolicyUrl,
        Boolean fastCompanyList,
        Integer filingLimit,
        Integer filingPdfParseLimit,
        Integer filingPdfMaxPages,
        String yahooSparkUrl,
        String yahooProxyUrl
) {
    public static final String DEFAULT_EASTMONEY_FUND_FLOW_URL = "https://push2.eastmoney.com/api/qt/ulist.np/get";
    public static final String DEFAULT_EASTMONEY_FUND_FLOW_MINUTE_URL = "https://push2.eastmoney.com/api/qt/stock/fflow/kline/get";
    public static final String DEFAULT_YAHOO_SPARK_URL = "https://query1.finance.yahoo.com/v8/finance/spark";
}

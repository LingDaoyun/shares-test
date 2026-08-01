package com.aistock.research.integration.tushare;

@FunctionalInterface
public interface TushareChipTransport {
    TushareHttpResponse post(TushareChipRequest request) throws Exception;
}

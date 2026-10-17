package com.aistock.research.integration.eastmoney;

/** 供应商确认的股票所属板块；不据此推断板块类型、事件受益或资金转移。 */
public record EastMoneyRelatedBoard(String code, String name) {
}

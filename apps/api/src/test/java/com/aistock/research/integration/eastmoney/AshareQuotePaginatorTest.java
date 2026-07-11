package com.aistock.research.integration.eastmoney;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AshareQuotePaginatorTest {

    @Test
    void continuesWhenProviderCapsRequestedPageSize() {
        AtomicInteger calls = new AtomicInteger();

        List<EastMoneyQuote> result = AshareQuotePaginator.collect(250, pageNumber -> {
            calls.incrementAndGet();
            int start = (pageNumber - 1) * 100;
            return new AshareQuotePage(
                    12_378,
                    IntStream.range(start, start + 100).mapToObj(this::quote).toList()
            );
        });

        assertThat(result).hasSize(250);
        assertThat(calls).hasValue(3);
        assertThat(result).extracting(EastMoneyQuote::symbol).doesNotHaveDuplicates();
    }

    private EastMoneyQuote quote(int index) {
        String symbol = "6" + String.format("%05d", index);
        return new EastMoneyQuote(
                symbol,
                "样本" + index,
                "上交所",
                "测试行业",
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("100000000"),
                new BigDecimal("20"),
                new BigDecimal("2"),
                new BigDecimal("20"),
                "分页测试",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-10T07:00:00Z")
        );
    }
}

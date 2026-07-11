package com.aistock.research.quality;

import com.aistock.research.trading.TradingAdvice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCompletenessServiceTest {

    private final EvidenceCompletenessService service = new EvidenceCompletenessService();

    @Test
    void shouldGateBuyAdviceWhenCriticalEvidenceIsMissing() {
        EvidenceCompleteness completeness = service.evaluate(EvidenceCompletenessInput.longTerm(
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                List.of("公告未复核")
        ));
        TradingAdvice add = new TradingAdvice("ADD", "分批加仓", 82, "可以买", List.of("估值较低"), List.of("单票上限"));

        TradingAdvice capped = service.gateAdvice(add, completeness);

        assertThat(completeness.score()).isLessThan(70);
        assertThat(completeness.status()).isEqualTo("INSUFFICIENT");
        assertThat(completeness.missingEvidence()).contains("近三年财报质量", "公告/定期报告反证", "行业估值对比");
        assertThat(capped.action()).isEqualTo("WAIT");
        assertThat(capped.actionLabel()).isEqualTo("观望");
        assertThat(capped.summary()).contains("证据完整度不足");
    }

    @Test
    void shouldAllowShortTermBuyWhenQuoteKlineFinancialAndIntradayArePresent() {
        EvidenceCompleteness completeness = service.evaluate(EvidenceCompletenessInput.shortTerm(
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                List.of()
        ));

        assertThat(completeness.score()).isGreaterThanOrEqualTo(70);
        assertThat(completeness.allowsBuy()).isTrue();
        assertThat(completeness.presentEvidence()).contains("实时行情", "估值字段", "近一年K线", "近三年财报质量", "尾盘/分时确认");
    }
}

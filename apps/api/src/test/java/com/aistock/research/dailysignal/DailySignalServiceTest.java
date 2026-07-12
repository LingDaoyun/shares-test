package com.aistock.research.dailysignal;

import com.aistock.research.mispricing.MispricedAsset;
import com.aistock.research.mispricing.MispricingEvidenceItem;
import com.aistock.research.mispricing.MispricingReport;
import com.aistock.research.mispricing.MispricingReviewResult;
import com.aistock.research.mispricing.MispricingRuleSet;
import com.aistock.research.mispricing.MispricingScoreBreakdown;
import com.aistock.research.mispricing.MispricingService;
import com.aistock.research.mispricing.StyleHeatSnapshot;
import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.quality.RecommendationEvidenceBundle;
import com.aistock.research.tech.TechEvidenceItem;
import com.aistock.research.tech.TechScoreBreakdown;
import com.aistock.research.tech.TechTrackedStock;
import com.aistock.research.tech.TechTrackingReport;
import com.aistock.research.tech.TechTrackingRuleSet;
import com.aistock.research.tech.TechTrackingService;
import com.aistock.research.trading.TradingAdvice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailySignalServiceTest {

    private final StubTechTrackingService techTrackingService = new StubTechTrackingService();
    private final StubMispricingService mispricingService = new StubMispricingService();
    private final DailySignalService service = new DailySignalService(techTrackingService, mispricingService);

    @Test
    void shouldMergeDsaPlaybooksWithExistingPools() {
        techTrackingService.report = techReport();
        mispricingService.report = mispricingReport();

        DailySignalReport report = service.report(6, 3, 3, null);

        assertThat(report.sourceProject()).isEqualTo("ZhuLinsen/daily_stock_analysis");
        assertThat(mispricingService.hotHeat).isEqualByComparingTo("82");
        assertThat(report.strategyPlaybooks()).extracting(StrategyPlaybook::name)
                .contains("shrink_pullback", "expectation_repricing", "growth_quality", "event_driven");
        DailyDecisionSignal inspur = report.signals().stream()
                .filter(signal -> "000977".equals(signal.symbol()))
                .findFirst()
                .orElseThrow();
        assertThat(inspur.action()).isEqualTo("add");
        assertThat(inspur.strategyTags()).contains("growth_quality", "shrink_pullback");
        assertThat(inspur.recommendedPrice()).isEqualByComparingTo("60.00");
        assertThat(inspur.marketTimestamp()).isEqualTo(Instant.parse("2026-07-01T07:00:00Z"));
        DailyDecisionSignal cmb = report.signals().stream()
                .filter(signal -> "600036".equals(signal.symbol()))
                .findFirst()
                .orElseThrow();
        assertThat(cmb.recommendedPrice()).isEqualByComparingTo("36.00");
        assertThat(cmb.marketTimestamp()).isEqualTo(Instant.parse("2026-07-01T07:01:00Z"));
        assertThat(report.marketContext().riskTags()).contains("热门方向高过热");
    }

    private TechTrackingReport techReport() {
        return new TechTrackingReport(
                "A股科技追踪池",
                2,
                1,
                "测试行情",
                List.of("测试方法"),
                List.of(new TechEvidenceItem("AI 算力", "政策和产业主线", "https://example.com/tech", 90)),
                new TechTrackingRuleSet(
                        new BigDecimal("80"),
                        new BigDecimal("20"),
                        new BigDecimal("120"),
                        new BigDecimal("40"),
                        new BigDecimal("5"),
                        new BigDecimal("8"),
                        new BigDecimal("10")
                ),
                List.of(new TechTrackedStock(
                        1,
                        "000977",
                        "浪潮信息",
                        "AI_INFRA",
                        "国产 AI 服务器",
                        "计算机设备",
                        new BigDecimal("60.00"),
                        Instant.parse("2026-07-01T07:00:00Z"),
                        new BigDecimal("-4.00"),
                        new BigDecimal("42.00"),
                        new BigDecimal("4.60"),
                        new BigDecimal("1200000000"),
                        new TechScoreBreakdown(
                                new BigDecimal("94"),
                                new BigDecimal("76"),
                                new BigDecimal("88"),
                                new BigDecimal("82"),
                                new BigDecimal("84")
                        ),
                        "WAIT_PULLBACK",
                        "回踩重点跟踪",
                        "政策、业绩和估值容错匹配度较好，等待回踩确认优先。",
                        new TradingAdvice(
                                "ADD",
                                "加仓",
                                70,
                                "今日回撤已接近规则阈值，可小仓试探。",
                                List.of("回撤接近阈值"),
                                List.of("先小仓", "放量下杀不追加")
                        ),
                        List.of("国产 AI 服务器主线明确", "政策和算力建设同向"),
                        List.of("服务器价格战会压缩毛利"),
                        List.of("等待回踩企稳"),
                        List.of("跌破 20 日线先减仓"),
                        List.of(new TechEvidenceItem("追涨纪律", "回踩确认优先", null, 80))
                )),
                Instant.parse("2026-07-02T00:00:00Z")
        );
    }

    private MispricingReport mispricingReport() {
        return new MispricingReport(
                "热门方向错杀估值池",
                2,
                1,
                "测试行情",
                List.of("测试方法"),
                new StyleHeatSnapshot(
                        "科技主线",
                        new BigDecimal("82"),
                        new BigDecimal("80"),
                        new BigDecimal("84"),
                        "高过热",
                        List.of("科技过热")
                ),
                new MispricingRuleSet(
                        new BigDecimal("70"),
                        new BigDecimal("18"),
                        new BigDecimal("2.50"),
                        new BigDecimal("78"),
                        new BigDecimal("4"),
                        new BigDecimal("7"),
                        1200
                ),
                List.of(new MispricingEvidenceItem("红利", "低估值资产承接价值", null, 80)),
                List.of(new MispricedAsset(
                        1,
                        "600036",
                        "招商银行",
                        "优质金融",
                        "银行",
                        new BigDecimal("36.00"),
                        Instant.parse("2026-07-01T07:01:00Z"),
                        new BigDecimal("-0.50"),
                        new BigDecimal("6.00"),
                        new BigDecimal("0.80"),
                        new BigDecimal("900000000"),
                        new MispricingScoreBreakdown(
                                new BigDecimal("82"),
                                new BigDecimal("88"),
                                new BigDecimal("90"),
                                new BigDecimal("82"),
                                new BigDecimal("88"),
                                new BigDecimal("86")
                        ),
                        "ACCUMULATE_WEAKNESS",
                        "错杀候选",
                        "质量、估值和弱势日条件匹配。",
                        new TradingAdvice(
                                "ADD",
                                "加仓",
                                82,
                                "满足错杀候选和弱势日纪律。",
                                List.of("估值达标"),
                                List.of("分批加仓")
                        ),
                        List.of("零售银行龙头", "估值低于长期质量水平"),
                        List.of("息差压力"),
                        List.of("弱势日分批"),
                        List.of("基本面恶化退出"),
                        List.of(new MispricingEvidenceItem("东方财富行情", "估值核验", "https://example.com/value", 80)),
                        new EvidenceCompleteness(
                                85,
                                "ENOUGH",
                                "证据可执行",
                                true,
                                List.of("实时行情", "估值字段", "近三年财报质量", "公告/定期报告反证"),
                                List.of(),
                                List.of()
                        ),
                        RecommendationEvidenceBundle.unavailable("600036"),
                        new MispricingReviewResult(
                                "PASSED",
                                "系统已核验：进入错杀候选",
                                "进入观察候选。",
                                List.of("估值达标"),
                                List.of("全市场流动性风险"),
                                List.of()
                        )
                )),
                Instant.parse("2026-07-02T01:00:00Z")
        );
    }

    private static final class StubTechTrackingService extends TechTrackingService {

        private TechTrackingReport report;

        private StubTechTrackingService() {
            super(null);
        }

        @Override
        public TechTrackingReport report(
                Integer limit,
                BigDecimal coreMaxPe,
                BigDecimal coreMaxPb,
                BigDecimal hardMaxPe,
                BigDecimal hardMaxPb
        ) {
            return report;
        }
    }

    private static final class StubMispricingService extends MispricingService {

        private MispricingReport report;
        private BigDecimal hotHeat;

        private StubMispricingService() {
            super(null, null);
        }

        @Override
        public MispricingReport report(Integer limit, BigDecimal hotHeat, BigDecimal maxPe, BigDecimal maxPb, BigDecimal minQuality) {
            this.hotHeat = hotHeat;
            return report;
        }
    }
}

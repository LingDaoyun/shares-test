package com.aistock.research.fiveday;

import com.aistock.research.firstboard.*;
import com.aistock.research.integration.eastmoney.*;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.aistock.research.fiveday.FiveDayModels.*;
import static com.aistock.research.fiveday.FiveDayContextModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FiveDayContextServiceTest {
    static final LocalDate DATE = LocalDate.parse("2026-09-07");
    final TradingClockService clock = new TradingClockService(Clock.fixed(Instant.parse("2026-09-08T01:00:00Z"), ZoneId.of("Asia/Shanghai")));
    final FirstBoardBaselineService baseline = mock(FirstBoardBaselineService.class);
    final FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
    final EastMoneyClient market = mock(EastMoneyClient.class);
    final FiveDayHistoryService history = mock(FiveDayHistoryService.class);
    final FiveDayContextEngine engine = mock(FiveDayContextEngine.class);
    final FiveDayEventEvidenceService events = mock(FiveDayEventEvidenceService.class);
    final FiveDayExecutionCheckService execution = mock(FiveDayExecutionCheckService.class);
    final RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
    final FiveDayService service = new FiveDayService(baseline, store, market, clock, new FiveDayEvaluator(clock), history, attestations, engine, events, execution);
    final Map<String, FirstBoardLeaderBaselineStock> stocks = new LinkedHashMap<>();
    int supportedCount = -1;

    @BeforeEach void setup() {
        when(history.latest(any(), anyBoolean(), anyBoolean(), anyString())).thenReturn(Optional.empty());
        when(history.latest(any(), anyBoolean(), anyBoolean())).thenReturn(Optional.empty());
        when(market.enrichDailyKLineTradingMetrics(anyString(), any(), any(), anyList())).thenAnswer(c -> c.getArgument(3));
        when(market.fetchFundFlowHistory(anyString(), any(), any())).thenAnswer(c -> bars(c.getArgument(0)).subList(20,25).stream()
            .map(b -> new EastMoneyFundFlowSnapshot(b.symbol(),null,new BigDecimal("1000000"),null,null,null,null,null,null,null,null,null,"fixture","fixture",null,b.tradeDate(),null)).toList());
        when(events.fetchPolicies(any())).thenReturn(new PolicyBundle("READY",List.of(),List.of()));
        when(events.review(anyString(),anyString(),anyString(),any())).thenReturn(new EventReview("NO_TITLE_RISK",List.of(),List.of()));
        when(events.relatedPolicies(anyString(),any())).thenReturn(List.of());
        when(engine.turning(anyString(),anyList(),any())).thenReturn(new Turning(true,BigDecimal.ONE,new BigDecimal("80"),List.of()));
        when(engine.returnPercent(anyString(),anyList(),any(),eq(3))).thenReturn(BigDecimal.ONE);
        context("NORMAL");
    }
    @AfterEach void cleanup() { service.close(); }

    @Test void coldFlatStocksCannotDisplaceAStrongSectorAndSlotsRotateAcrossStrongSectors() {
        for (int i=1;i<=101;i++) stock(String.format("600%03d",i),"主线甲");
        stock("601001","主线乙"); stock("601002","冷行业");
        Snapshot result = scan();
        assertThat(result.ruleVersion()).isEqualTo(VERSION_V2);
        assertThat(result.coverage().priceQualifiedCount()).isEqualTo(102);
        assertThat(result.coverage().reviewedCount()).isEqualTo(100);
        assertThat(result.coverage().unreviewedCount()).isEqualTo(2);
        assertThat(result.candidates()).extracting(Candidate::symbol).contains("601001").doesNotContain("601002");
        verify(market,never()).fetchFundFlowHistory(eq("601002"),any(),any());
        assertThat(result.context().assessments()).allMatch(a -> "ELIGIBLE".equals(a.decision()));
        verify(history).capture(argThat(s -> VERSION_V2.equals(s.ruleVersion()) && s.context()!=null));
        assertThat(service.snapshot(false,false).ruleVersion()).isEqualTo(VERSION);
    }

    @Test void marketCoverageUsesOriginalSupportedUniverseBeforeBaselineFailures() {
        stock("600001","主线甲"); supportedCount = 500;
        context("UNKNOWN");
        Snapshot result = scan();
        assertThat(result.context()).isNotNull();
        verify(engine).analyze(eq(DATE),any(),anyList(),anyList(),eq(500));
        verify(history,never()).capture(any());
    }

    @Test void weakMarketAndUnconfirmedTurningRemainWatch() {
        stock("600001","主线甲"); context("RISK_OFF");
        when(engine.turning(anyString(),anyList(),any())).thenReturn(new Turning(false,BigDecimal.ZERO,new BigDecimal("50"),List.of("尚未转强")));
        Snapshot result = scan();
        assertThat(result.context().assessments()).singleElement().satisfies(a -> {
            assertThat(a.decision()).isEqualTo("WATCH");
            assertThat(a.reasons()).anyMatch(r -> r.contains("RISK_OFF"));
        });
    }

    @Test void titleRiskCannotBePromotedAndUnknownSourceStaysUnknown() {
        stock("600001","主线甲"); stock("600002","主线甲");
        when(events.review(eq("600001"),anyString(),anyString(),any())).thenReturn(new EventReview("TITLE_RISK",List.of(),List.of("诉讼线索")));
        when(events.review(eq("600002"),anyString(),anyString(),any())).thenReturn(new EventReview("UNKNOWN",List.of(),List.of("源失败")));
        Snapshot result = scan();
        assertThat(result.context().assessments()).extracting(CandidateAssessment::decision).containsExactlyInAnyOrder("AVOID","UNKNOWN");
    }

    @Test void unknownMarketIsReadableButNotFrozenAsACompleteSignal() {
        stock("600001","主线甲"); context("UNKNOWN");
        Snapshot result = scan();
        assertThat(result.context().market().state()).isEqualTo("UNKNOWN");
        assertThat(result.forwardEligible()).isFalse();
        verify(history,never()).capture(any());
    }

    @Test void allUnknownSectorsCannotBecomeAFrozenEmptySuccess() {
        stock("600001", "行业未知");
        sectorContext(List.of(new Sector("行业未知", "UNKNOWN", 0, 10, 10, new BigDecimal("100"),
                null, null, null, null, null, null, List.of("600001"), List.of("行业归属未知"))));
        Snapshot result = scan();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.candidates()).isEmpty();
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("行业") && gap.contains("未固化"));
        verify(history, never()).capture(any());
    }

    @Test void missingSectorAssessmentsCannotBecomeAFrozenEmptySuccess() {
        stock("600001", "主线甲");
        sectorContext(List.of());
        Snapshot result = scan();
        assertThat(result.status()).isEqualTo("FAILED");
        verify(history, never()).capture(any());
    }

    @Test void aKnownWeakSectorKeepsAnEmptyScanValidAlongsideASmallUnsupportedSector() {
        stock("600001", "冷行业");
        sectorContext(List.of(new Sector("冷行业", "WEAK", 1, 10, 10, new BigDecimal("100"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of("600001"), List.of("行业5日收益未为正")),
                new Sector("小样本行业", "UNKNOWN", 0, 3, 3, new BigDecimal("100"),
                        null, null, null, null, null, null, List.of(), List.of("完整样本不足5只"))));
        Snapshot result = scan();
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.candidates()).isEmpty();
        verify(history).capture(any());
    }

    @Test void v2AttestationRequiresAPassedIndependentEntryCheck() {
        Candidate c = FiveDayOutcomeCalculatorTest.candidate("600001",true,true);
        Snapshot s = new Snapshot("READY",VERSION_V2,DATE,Instant.parse("2026-09-07T08:00:00Z"),true,false,false,"fixture",Rules.defaults(),new Coverage(1,1,1,1,1,0,0),List.of(c),List.of(),null);
        when(history.latest(DATE,false,false,VERSION_V2)).thenReturn(Optional.of(s));
        when(execution.check(s,c)).thenReturn(new FiveDayExecutionCheckService.ExecutionCheck(c.symbol(),"UNKNOWN",Instant.now(),"UNKNOWN",null,List.of("报价缺失")));
        assertThatThrownBy(() -> service.attestation(c.symbol(),false,false,VERSION_V2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(attestations);
    }

    Snapshot scan() {
        when(baseline.leaderAccess(any())).thenReturn(new FirstBoardLeaderBaselineAccess(FirstBoardAlertStatus.READY,DATE.plusDays(1),DATE,Instant.parse("2026-09-07T08:00:00Z"),stocks,null,stocks.size(),supportedCount < 0 ? stocks.size() : supportedCount,0,0,List.of()));
        service.start(false,false,VERSION_V2);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(service.snapshot(false,false,VERSION_V2).status()).isNotEqualTo("SCANNING"));
        return service.snapshot(false,false,VERSION_V2);
    }
    void context(String state) {
        when(engine.analyze(any(),any(),anyList(),anyList(),anyInt())).thenReturn(new Context(Instant.parse("2026-09-08T01:00:00Z"),new Market(state,300,300,new BigDecimal("100"),new BigDecimal("60"),BigDecimal.ONE,BigDecimal.ZERO,List.of()),List.of(sector("主线甲",1),sector("主线乙",2)),List.of(),List.of(),"UNKNOWN",List.of()));
    }
    void sectorContext(List<Sector> sectors) {
        when(engine.analyze(any(), any(), anyList(), anyList(), anyInt())).thenReturn(new Context(
                Instant.parse("2026-09-08T01:00:00Z"), new Market("NORMAL", 300, 300, new BigDecimal("100"),
                new BigDecimal("60"), BigDecimal.ONE, BigDecimal.ZERO, List.of()), sectors, List.of(), List.of(), "UNKNOWN", List.of()));
    }
    Sector sector(String industry,int rank) { return new Sector(industry,"STRONG",rank,10,10,new BigDecimal("100"),BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,new BigDecimal("60"),new BigDecimal("60"),List.of(),List.of()); }
    void stock(String code,String industry) {
        List<EastMoneyKLine> b=bars(code);
        stocks.put(code,new FirstBoardLeaderBaselineStock(code,"示例公司",industry,FirstBoardBoard.MAIN,DATE,null,null,null,null,null,null,0,null,null,null,null,false,b.stream().map(r -> new FirstBoardBar(r.tradeDate(),r.open(),r.close(),r.high(),r.low(),r.volume())).toList()));
        when(store.recentBars(eq(code),eq(DATE),anyInt())).thenReturn(b);
    }
    List<EastMoneyKLine> bars(String code) {
        List<LocalDate> dates=new ArrayList<>(); LocalDate d=DATE;
        for(int i=0;i<25;i++){dates.add(0,d);d=clock.previousTradingDay(d);}
        List<EastMoneyKLine> result=new ArrayList<>();
        for(int i=0;i<25;i++) result.add(new EastMoneyKLine(code,dates.get(i),new BigDecimal("10"),new BigDecimal("10"),new BigDecimal("10.2"),new BigDecimal("9.8"),new BigDecimal("6000000"),new BigDecimal("60000000"),new BigDecimal(i<20?"2":"3")));
        return result;
    }
}

package com.aistock.research.leader;

import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EastMoneyDragonTigerGatewayTest {

    private static final LocalDate SCAN_DATE = LocalDate.parse("2026-09-03");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesDeduplicatesAndClassifiesSeatsBeforeApplyingSoftAdjustment() throws Exception {
        Map<String, JsonNode> reports = Map.of(
                "RPT_DAILYBILLBOARD_PROFILE", json(profileFixture()),
                "RPT_BILLBOARD_DAILYDETAILSBUY", json(buyFixture()),
                "RPT_BILLBOARD_DAILYDETAILSSELL", json(sellFixture()));
        EastMoneyDragonTigerGateway gateway = new EastMoneyDragonTigerGateway(
                objectMapper, new TradingClockService(),
                (report, page, start, end) -> reports.get(report));

        DragonTigerGateway.DragonTigerBatch batch = gateway.fetch(
                SCAN_DATE, List.of("600000", "600001", "600099"));

        PotentialLeaderSnapshot.DragonTigerEvidence diverse = batch.evidenceBySymbol().get("600000");
        assertThat(diverse.adjustment()).isEqualByComparingTo("10.00");
        assertThat(diverse.seats().stream().filter(seat -> "B1".equals(seat.branchCode()))).hasSize(1);
        assertThat(diverse.seats()).anySatisfy(seat -> assertThat(seat.institution()).isTrue());
        assertThat(diverse.seats()).anySatisfy(seat -> assertThat(seat.stockConnect()).isTrue());
        assertThat(diverse.seats()).filteredOn(seat -> seat.branchCode() == null)
                .extracting(PotentialLeaderSnapshot.Seat::branchName)
                .containsExactly("无代码营业部");

        PotentialLeaderSnapshot.DragonTigerEvidence concentrated = batch.evidenceBySymbol().get("600001");
        assertThat(concentrated.adjustment()).isEqualByComparingTo("-5.00");
        assertThat(batch.evidenceBySymbol().get("600099").status())
                .isEqualTo(PotentialLeaderSnapshot.DisclosureStatus.NOT_DISCLOSED);
        assertThat(batch.evidenceBySymbol().get("600099").adjustment()).isEqualByComparingTo("0");
    }

    @Test
    void convertsTotalFetchFailureIntoPerSymbolEvidence() {
        EastMoneyDragonTigerGateway gateway = new EastMoneyDragonTigerGateway(
                objectMapper, new TradingClockService(),
                (report, page, start, end) -> {
                    throw new IllegalStateException("upstream unavailable");
                });

        DragonTigerGateway.DragonTigerBatch failed = gateway.fetch(SCAN_DATE, List.of("600000"));

        assertThat(failed.evidenceBySymbol().get("600000").status())
                .isEqualTo(PotentialLeaderSnapshot.DisclosureStatus.FETCH_FAILED);
        assertThat(failed.evidenceBySymbol().get("600000").adjustment()).isEqualByComparingTo("0");
        assertThat(failed.dataGaps()).singleElement().asString().contains("upstream unavailable");
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private String profileFixture() {
        return """
                {"success":true,"result":{"pages":1,"data":[
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03 00:00:00","BILLBOARD_NET_AMT":50,"ACCUM_AMOUNT":1000},
                  {"SECURITY_CODE":"600001","TRADE_DATE":"2026-09-03 00:00:00","BILLBOARD_NET_AMT":-40,"ACCUM_AMOUNT":1000}
                ]}}
                """;
    }

    private String buyFixture() {
        return """
                {"success":true,"result":{"pages":1,"data":[
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"B1","OPERATEDEPT_NAME":"甲营业部","BUY":250},
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"B1","OPERATEDEPT_NAME":"甲营业部别名","BUY":250},
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"B2","OPERATEDEPT_NAME":"机构专用","BUY":250},
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"B3","OPERATEDEPT_NAME":"沪股通专用","BUY":250},
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"B4","OPERATEDEPT_NAME":"乙营业部","BUY":250},
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"","OPERATEDEPT_NAME":"  无代码营业部  ","BUY":250},
                  {"SECURITY_CODE":"600001","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"X1","OPERATEDEPT_NAME":"集中营业部","BUY":900}
                ]}}
                """;
    }

    private String sellFixture() {
        return """
                {"success":true,"result":{"pages":1,"data":[
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"S1","OPERATEDEPT_NAME":"卖方营业部","SELL":1200},
                  {"SECURITY_CODE":"600001","TRADE_DATE":"2026-09-03","OPERATEDEPT_CODE":"X1","OPERATEDEPT_NAME":"集中营业部","SELL":940}
                ]}}
                """;
    }
}

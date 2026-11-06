package com.aistock.research.integration.eastmoney;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class SinaDailyKLineParserTest {
    private static final String SYMBOL = "600000";
    private static final LocalDate BEGIN = LocalDate.of(2026, 10, 20);
    private static final LocalDate YESTERDAY = BEGIN.plusDays(1);
    private static final LocalDate TODAY = BEGIN.plusDays(2);
    private static final LocalDateTime NOON = TODAY.atTime(12, 0);
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final String FACTORS = "var sh600000qfq={\"total\":2,\"data\":["
            + "{\"d\":\"2026-10-21\",\"f\":\"1.0000\"},"
            + "{\"d\":\"1900-01-01\",\"f\":\"2\"}]};";
    private static final String DAILY = "[" + bar("2026-10-20", "20", "22", "24", "18", "12345")
            + "," + bar("2026-10-21", "12", "13", "14", "11", "60000") + "]";

    @Test
    void adjustsEveryPriceAsOfItsFactorAndConvertsSharesToHandsWithoutInventingMissingValues() {
        EastMoneyKLineSeries result = historical(DAILY, FACTORS, BEGIN, YESTERDAY);

        assertThat(result.complete()).isTrue();
        assertThat(result.sourceName()).isEqualTo("SINA_QFQ_DAILY");
        assertThat(result.rows()).extracting(EastMoneyKLine::tradeDate).containsExactly(BEGIN, YESTERDAY);
        EastMoneyKLine first = result.rows().get(0);
        assertThat(first.symbol()).isEqualTo(SYMBOL);
        assertThat(first.open()).isEqualByComparingTo("10");
        assertThat(first.close()).isEqualByComparingTo("11");
        assertThat(first.high()).isEqualByComparingTo("12");
        assertThat(first.low()).isEqualByComparingTo("9");
        assertThat(first.volume()).isEqualByComparingTo("123.45");
        assertThat(first.amount()).isNull();
        assertThat(first.turnoverRate()).isNull();
        assertThat(result.rows().get(1).close()).isEqualByComparingTo("13");
    }

    @Test
    void retainsPrecisionBeyondTwoDecimalsAndSortsRawDatesBeforeCropping() {
        String daily = "[" + bar("2026-10-21", "12", "13", "14", "11", "60000")
                + "," + bar("2026-10-20", "20", "22", "24", "18", "12345") + "]";
        EastMoneyKLineSeries result = historical(daily, FACTORS.replace("\"f\":\"2\"", "\"f\":\"3\""), BEGIN, BEGIN);

        assertThat(result.complete()).isTrue();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).open()).isBetween(new BigDecimal("6.666666666666"), new BigDecimal("6.666666666667"));
    }

    @ParameterizedTest
    @CsvSource({"600000,sh600000", "002881,sz002881", "920001,bj920001"})
    void validatesTheExchangeQualifiedFactorIdentity(String symbol, String factorSymbol) {
        EastMoneyKLineSeries result = SinaDailyKLineParser.parse(symbol, DAILY,
                FACTORS.replace("sh600000", factorSymbol), BEGIN, YESTERDAY, null, NOON);
        assertThat(result.complete()).isTrue();
        assertThat(result.rows()).allMatch(row -> row.symbol().equals(symbol));
    }

    @ParameterizedTest
    @CsvSource({"002881,sz002881", "920000,bj920000"})
    void acceptsTheTrailingBlockCommentFromSinasRealQfqResponse(String symbol, String factorSymbol) {
        String factors = FACTORS.replace("sh600000", factorSymbol).replace("};", "}")
                + "\n/* EFuBwDtYiPVP+Fn910Au8M/mvXF7agD2oS5oemVgxW4piasYTEuj7EwZWdfASuJ2ZJnxJc2gg3aL3nqzBW32sVDdq */";
        EastMoneyKLineSeries result = SinaDailyKLineParser.parse(symbol, DAILY, factors,
                BEGIN, YESTERDAY, null, NOON);
        assertThat(result.complete()).as(result.detail()).isTrue();
        assertThat(result.rows().get(0).close()).isEqualByComparingTo("11");
    }

    @Test
    void appendsTodayFromAValidQuoteWithoutDividingItsAlreadyHandVolumeAgain() {
        EastMoneyKLineSeries result = SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS,
                BEGIN, TODAY, quote(TODAY.atTime(11, 30)), NOON);

        assertThat(result.complete()).isTrue();
        assertThat(result.rows()).hasSize(3);
        EastMoneyKLine today = result.rows().get(2);
        assertThat(today.tradeDate()).isEqualTo(TODAY);
        assertThat(today.open()).isEqualByComparingTo("13");
        assertThat(today.close()).isEqualByComparingTo("13.5");
        assertThat(today.high()).isEqualByComparingTo("14");
        assertThat(today.low()).isEqualByComparingTo("12");
        assertThat(today.volume()).isEqualByComparingTo("321.45");
        assertThat(today.amount()).isEqualByComparingTo("432100");
        assertThat(today.turnoverRate()).isEqualByComparingTo("2.5");
    }

    @Test
    void replacesTheExistingTodayBarWithActualCurrentQuote() {
        String withToday = DAILY.substring(0, DAILY.length() - 1) + ","
                + bar("2026-10-22", "12", "12", "12", "12", "100") + "]";
        EastMoneyKLineSeries result = SinaDailyKLineParser.parse(SYMBOL, withToday, FACTORS,
                BEGIN, TODAY, quote(TODAY.atTime(11, 30)), NOON);
        assertThat(result.complete()).isTrue();
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(2).close()).isEqualByComparingTo("13.5");
        assertThat(result.rows().get(2).volume()).isEqualByComparingTo("321.45");
    }

    @Test
    void doesNotTrustAnExistingTodayBarWithoutAValidQuote() {
        String withToday = DAILY.substring(0, DAILY.length() - 1) + ","
                + bar("2026-10-22", "12", "12", "12", "12", "100") + "]";
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, withToday, FACTORS, BEGIN, TODAY, null, NOON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "stale"})
    void retainsVerifiedHistoryWhenTheCurrentQuoteIsInvalid(String problem) {
        String withToday = DAILY.substring(0, DAILY.length() - 1) + ","
                + bar("2026-10-22", "12", "12", "12", "12", "100") + "]";
        EastMoneyQuote current = problem.equals("missing") ? null : quote(TODAY.atTime(9, 57, 59));
        EastMoneyKLineSeries result = SinaDailyKLineParser.parse(SYMBOL, withToday, FACTORS,
                BEGIN, TODAY, current, TODAY.atTime(10, 0));

        assertThat(result.complete()).isFalse();
        assertThat(result.rows()).extracting(EastMoneyKLine::tradeDate).containsExactly(BEGIN, YESTERDAY);
        assertThat(result.rows().get(0).close()).isEqualByComparingTo("11");
        assertThat(result.detail()).contains(problem.equals("missing") ? "缺少同股票同交易日" : "超过 120 秒");
    }

    @ParameterizedTest
    @CsvSource({
            "10:00:00,09:58:00,true", "10:00:00,09:57:59,false",
            "10:00:00,10:00:01,false", "12:00:00,11:30:00,true",
            "12:00:00,11:28:00,true", "12:00:00,11:27:59,false",
            "12:00:00,11:59:00,true", "12:12:00,12:05:00,true",
            "13:00:00,11:30:00,false",
            "13:00:00,13:00:00,true", "15:00:00,14:59:59,false",
            "15:00:00,15:00:00,true", "16:00:00,15:00:00,true",
            "16:00:00,15:30:00,true", "09:00:00,09:00:00,false"
    })
    void validatesQuoteTimeAtTradingLunchAndCloseBoundaries(String now, String marketTime, boolean complete) {
        EastMoneyKLineSeries result = SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY,
                quote(LocalDateTime.parse(TODAY + "T" + marketTime)), LocalDateTime.parse(TODAY + "T" + now));
        assertThat(result.complete()).as(result.detail()).isEqualTo(complete);
    }

    @Test
    void rejectsQuoteIdentityAndDateConflictsInsteadOfBorrowingAnotherSnapshot() {
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY,
                quote("600001", TODAY, TODAY.atTime(11, 30), "13", "14", "12", "321", null, null), NOON));
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY,
                quote(SYMBOL, YESTERDAY, TODAY.atTime(11, 30), "13", "14", "12", "321", null, null), NOON));
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY,
                quote(SYMBOL, TODAY, YESTERDAY.atTime(11, 30), "13", "14", "12", "321", null, null), NOON));
    }

    @Test
    void retainsMissingQuoteAmountAndTurnoverButRejectsInvalidPriceAndVolume() {
        EastMoneyQuote missingOptional = quote(SYMBOL, TODAY, TODAY.atTime(11, 30), "13", "14", "12", "321", null, null);
        EastMoneyKLineSeries valid = SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY, missingOptional, NOON);
        assertThat(valid.complete()).isTrue();
        assertThat(valid.rows().get(2).amount()).isNull();
        assertThat(valid.rows().get(2).turnoverRate()).isNull();
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY,
                quote(SYMBOL, TODAY, TODAY.atTime(11, 30), null, "14", "12", "321", null, null), NOON));
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY,
                quote(SYMBOL, TODAY, TODAY.atTime(11, 30), "13", "12", "12", "321", null, null), NOON));
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS, BEGIN, TODAY,
                quote(SYMBOL, TODAY, TODAY.atTime(11, 30), "13", "14", "12", "-1", null, null), NOON));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "var sz600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"1\"}]};",
            "{\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"1\"}]}",
            "var sh600000qfq={\"total\":2,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"1\"}]};",
            "var sh600000qfq={\"total\":0,\"data\":[]};",
            "var sh600000qfq={\"total\":2,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"1\"},{\"d\":\"1900-01-01\",\"f\":\"2\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"0\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"-1\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"2\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"2026-10-21\",\"f\":\"1\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"2026-02-30\",\"f\":\"1\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"2026-10-23\",\"f\":\"1\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"NaN\"}]};",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"1\"}]};alert(1);",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"1\"}]};/* note */alert(1);/* note */",
            "var sh600000qfq={\"total\":1,\"data\":[{\"d\":\"1900-01-01\",\"f\":\"1\"}]};/* unterminated"
    })
    void rejectsUnverifiedOrIncompleteFactorPayloads(String factors) {
        assertIncomplete(historical(DAILY, factors, BEGIN, YESTERDAY));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"day\":\"2026-10-19\",\"open\":\"0\",\"close\":\"11\",\"high\":\"12\",\"low\":\"9\",\"volume\":\"100\"}",
            "{\"day\":\"2026-10-19\",\"open\":\"10\",\"close\":\"11\",\"high\":\"9\",\"low\":\"8\",\"volume\":\"100\"}",
            "{\"day\":\"2026-10-19\",\"open\":\"10\",\"close\":\"11\",\"high\":\"12\",\"low\":\"11\",\"volume\":\"100\"}",
            "{\"day\":\"2026-10-19\",\"open\":\"10\",\"close\":\"11\",\"high\":\"12\",\"low\":\"9\",\"volume\":\"-1\"}",
            "{\"day\":\"2026-10-19\",\"open\":\"NaN\",\"close\":\"11\",\"high\":\"12\",\"low\":\"9\",\"volume\":\"100\"}",
            "{\"day\":\"2026-02-30\",\"open\":\"10\",\"close\":\"11\",\"high\":\"12\",\"low\":\"9\",\"volume\":\"100\"}",
            "{\"day\":\"2026-10-23\",\"open\":\"10\",\"close\":\"11\",\"high\":\"12\",\"low\":\"9\",\"volume\":\"100\"}",
            "{\"day\":\"2026-10-19\",\"open\":\"10\",\"close\":\"11\",\"high\":\"12\",\"low\":\"9\"}",
            "null"
    })
    void validatesBadRawRowsEvenOutsideTheRequestedWindow(String badRow) {
        String response = DAILY.substring(0, DAILY.length() - 1) + "," + badRow + "]";
        assertIncomplete(historical(response, FACTORS, BEGIN, YESTERDAY));
    }

    @Test
    void rejectsDuplicatesOutsideTheWindowAndInvalidTodayBeforeQuoteReplacement() {
        String outside = bar("2026-10-19", "10", "11", "12", "9", "100");
        assertIncomplete(historical(DAILY.substring(0, DAILY.length() - 1) + "," + outside + "," + outside + "]",
                FACTORS, BEGIN, YESTERDAY));
        String badToday = bar("2026-10-22", "0", "11", "12", "9", "100");
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, DAILY.substring(0, DAILY.length() - 1) + "," + badToday + "]",
                FACTORS, BEGIN, TODAY, quote(TODAY.atTime(11, 30)), NOON));
    }

    @Test
    void preservesBoundaryGapChecksAndDoesNotLetOneCurrentQuoteFillMissingHistory() {
        assertIncomplete(historical(DAILY, FACTORS, BEGIN.minusDays(11), YESTERDAY));
        assertIncomplete(historical("[" + bar("2026-10-01", "10", "11", "12", "9", "100") + "]",
                FACTORS, BEGIN.minusDays(19), YESTERDAY));
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, "[]", FACTORS, BEGIN, TODAY, quote(TODAY.atTime(11, 30)), NOON));
        assertIncomplete(historical(DAILY, FACTORS, YESTERDAY, BEGIN));
    }

    @Test
    void doesNotLetTodaysQuoteConcealAStaleHistoricalTail() {
        String stale = "[" + bar("2026-10-01", "10", "11", "12", "9", "100") + "]";
        assertIncomplete(SinaDailyKLineParser.parse(SYMBOL, stale, FACTORS,
                LocalDate.of(2026, 10, 1), TODAY, quote(TODAY.atTime(11, 30)), NOON));
    }

    @Test
    void retainsVerifiedShortHistoryWithoutAppendingTodayWhenItsBoundaryIsIncomplete() {
        EastMoneyKLineSeries result = SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS,
                BEGIN.minusDays(11), TODAY, quote(TODAY.atTime(11, 30)), NOON);

        assertThat(result.complete()).isFalse();
        assertThat(result.rows()).extracting(EastMoneyKLine::tradeDate).containsExactly(BEGIN, YESTERDAY);
        assertThat(result.rows().get(0).close()).isEqualByComparingTo("11");
        assertThat(result.detail()).startsWith("解析 2/2 行，分片 1/1").contains("起始边界缺口过大");
    }

    @Test
    void keepsInvalidFactorOrOhlcResponsesEmptyEvenForATodayWindow() {
        assertThat(SinaDailyKLineParser.parse(SYMBOL, DAILY, FACTORS.replace("sh600000", "sz600000"),
                BEGIN, TODAY, quote(TODAY.atTime(11, 30)), NOON).rows()).isEmpty();
        String bad = "[" + bar("2026-10-20", "0", "11", "12", "9", "100") + "]";
        assertThat(SinaDailyKLineParser.parse(SYMBOL, bad, FACTORS,
                BEGIN, TODAY, quote(TODAY.atTime(11, 30)), NOON).rows()).isEmpty();
    }

    private static EastMoneyKLineSeries historical(String json, String factors, LocalDate begin, LocalDate end) {
        return SinaDailyKLineParser.parse(SYMBOL, json, factors, begin, end, null, NOON);
    }

    private static void assertIncomplete(EastMoneyKLineSeries result) {
        assertThat(result.complete()).isFalse();
        assertThat(result.detail()).isNotBlank();
    }

    private static String bar(String day, String open, String close, String high, String low, String volume) {
        return "{\"day\":\"" + day + "\",\"open\":\"" + open + "\",\"close\":\"" + close
                + "\",\"high\":\"" + high + "\",\"low\":\"" + low + "\",\"volume\":\"" + volume + "\"}";
    }

    private static EastMoneyQuote quote(LocalDateTime timestamp) {
        return quote(SYMBOL, TODAY, timestamp, "13", "14", "12", "321.45", "432100", "2.5");
    }

    private static EastMoneyQuote quote(String symbol, LocalDate tradeDate, LocalDateTime timestamp,
                                        String open, String high, String low, String volume, String amount, String turnover) {
        return new EastMoneyQuote(symbol, "fixture", "沪市", null, new BigDecimal("13.5"), null,
                decimal(turnover), decimal(volume), decimal(amount), null, null, null, "fixture", null,
                NOON.atZone(CHINA).toInstant(), tradeDate, timestamp.atZone(CHINA).toInstant(), null,
                decimal(open), decimal(high), decimal(low));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}

package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermIntradayPullbackEvaluatorTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    private final ShortTermIntradayPullbackEvaluator evaluator = new ShortTermIntradayPullbackEvaluator();

    @Test
    void confirmsOrderedShrinkingPullbackAndSubsequentVolumeBreakout() {
        ShortTermIntradayPullbackSignal signal = evaluate(pattern());

        assertThat(signal.status()).isEqualTo("CONFIRMED");
        assertThat(signal.confirmed()).isTrue();
        assertThat(signal.pullbackMinute()).isEqualTo(DATE.atTime(13, 30));
        assertThat(signal.breakoutMinute()).isEqualTo(DATE.atTime(13, 40));
        assertThat(signal.platformHigh()).isEqualByComparingTo("10.14");
        assertThat(signal.pullbackLow()).isEqualByComparingTo("10.02");
        assertThat(signal.pullbackVolumeRatio()).isEqualByComparingTo("0.60");
        assertThat(signal.breakoutVolumeRatio()).isGreaterThan(new BigDecimal("2"));
        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("13:30") && reason.contains("0.60"));
        assertThat(signal.dataGaps()).isEmpty();
    }

    @Test
    void refusesBreakoutWithoutEnoughVolume() {
        List<EastMoneyIntradayPoint> points = pattern();
        replaceBar(points, 3, "10.15", "10.20", "10.04", "100");
        assertThat(evaluate(points).status()).isEqualTo("WATCH");
        assertThat(evaluate(points).reasons()).anyMatch(reason -> reason.contains("量比"));
    }

    @Test
    void acceptsTheExactTouchAndVolumeThresholds() {
        List<EastMoneyIntradayPoint> points = pattern();
        replaceBar(points, 2, "10.03", "10.10", "10.03", "80");
        replaceBar(points, 3, "10.15", "10.20", "10.04", "140");

        ShortTermIntradayPullbackSignal signal = evaluate(points);
        assertThat(signal.confirmed()).isTrue();
        assertThat(signal.pullbackVolumeRatio()).isEqualByComparingTo("0.8");
        assertThat(signal.breakoutVolumeRatio()).isEqualByComparingTo("1.5");
    }

    @Test
    void refusesPullbackWithoutShrinkingVolumeOrWithoutADecliningClose() {
        List<EastMoneyIntradayPoint> points = pattern();
        replaceBar(points, 2, "10.03", "10.10", "10.02", "81");
        assertThat(evaluate(points).confirmed()).isFalse();
        replaceBar(points, 2, "10.13", "10.14", "10.02", "60");
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void requiresTheMinuteLowToRespectItsOwnAveragePrice() {
        List<EastMoneyIntradayPoint> points = pattern();
        EastMoneyIntradayPoint point = points.get(23);
        points.set(23, with(point, point.volume(), new BigDecimal("10.025"), point.low()));
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void refusesAPullbackThatNeverTouchesTheAverageLine() {
        List<EastMoneyIntradayPoint> points = pattern();
        replaceBar(points, 2, "10.07", "10.10", "10.04", "60");
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void requiresACompactTwoBarPlatform() {
        List<EastMoneyIntradayPoint> points = pattern();
        replaceBar(points, 0, "10.10", "10.30", "10.08", "100");
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void allowsBreakoutOnTheThirdBarButNotTheFourth() {
        List<EastMoneyIntradayPoint> points = firstThreeBars();
        appendBar(points, "10.09", "10.12", "10.03", "90");
        appendBar(points, "10.10", "10.12", "10.03", "90");
        appendBar(points, "10.15", "10.20", "10.04", "180");
        assertThat(evaluate(points).confirmed()).isTrue();
        replaceBar(points, 5, "10.11", "10.12", "10.03", "90");
        appendBar(points, "10.15", "10.20", "10.04", "180");
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void doesNotTreatAnEarlierBreakoutAsConfirmationForALaterPullback() {
        List<EastMoneyIntradayPoint> points = new ArrayList<>();
        appendBar(points, "10.15", "10.20", "10.04", "180");
        appendBar(points, "10.10", "10.14", "10.08", "100");
        appendBar(points, "10.12", "10.14", "10.08", "100");
        appendBar(points, "10.03", "10.10", "10.02", "60");
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void rejectsLosingThePullbackLowBeforeBreakout() {
        List<EastMoneyIntradayPoint> points = firstThreeBars();
        appendBar(points, "10.09", "10.12", "10.01", "90");
        appendBar(points, "10.15", "10.20", "10.04", "180");
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void rejectsPostBreakoutAverageLineLossEvenWhenPriceRecovers() {
        List<EastMoneyIntradayPoint> points = pattern();
        points.add(point(41, "10.16", "10.20", "9.99", "100"));
        points.add(point(42, "10.16", "10.20", "10.05", "100"));
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void rejectsPostBreakoutPullbackLowLossAboveAverageEvenWhenPriceRecovers() {
        List<EastMoneyIntradayPoint> points = pattern();
        points.add(point(41, "10.01", "10.20", "10.01", "100"));
        points.add(point(42, "10.16", "10.20", "10.05", "100"));

        ShortTermIntradayPullbackSignal signal = evaluate(points);
        assertThat(signal.status()).isEqualTo("WATCH");
        assertThat(signal.confirmed()).isFalse();
        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("突破后") && reason.contains("回踩低点"));
    }

    @Test
    void requiresLatestCompletedMinuteAbovePlatformAndAverageLine() {
        List<EastMoneyIntradayPoint> points = pattern();
        points.add(point(41, "10.13", "10.20", "10.05", "100"));
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    @Test
    void excludesCurrentMinuteAndFutureBarsBeforeFieldValidation() {
        List<EastMoneyIntradayPoint> points = pattern();
        points.add(new EastMoneyIntradayPoint("600041", DATE.atTime(13, 41),
                null, null, null, null, null, null, null));
        points.add(new EastMoneyIntradayPoint("600041", DATE.atTime(14, 0),
                null, null, null, null, null, null, null));
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 41, 20)).confirmed()).isTrue();
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 40, 59)).confirmed()).isFalse();
    }

    @Test
    void excludesOtherTradingDaysBeforeFieldValidation() {
        List<EastMoneyIntradayPoint> points = pattern();
        points.add(new EastMoneyIntradayPoint("600041", DATE.minusDays(1).atTime(13, 10),
                null, null, null, null, null, null, null));
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 41)).confirmed()).isTrue();
        assertThat(evaluator.evaluate(points.subList(40, 41), DATE, DATE.atTime(13, 41)).status())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void refusesMissingMinuteInsteadOfFillingTenMinuteBars() {
        List<EastMoneyIntradayPoint> points = pattern();
        points.remove(24);
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 41)).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void refusesMissingTailMinuteInsteadOfConfirmingAnOldBreakout() {
        assertThat(evaluator.evaluate(pattern(), DATE, DATE.atTime(13, 43)).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void refusesAHoleBetweenPullbackAndLaterBreakout() {
        List<EastMoneyIntradayPoint> points = firstThreeBars();
        appendBar(points, "10.09", "10.12", "10.03", "90");
        appendBar(points, "10.15", "10.20", "10.04", "180");
        points.subList(30, 40).clear();
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 51)).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void acceptsIdenticalDuplicatesButRefusesConflictingDuplicates() {
        List<EastMoneyIntradayPoint> points = pattern();
        points.add(points.get(24));
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 41)).confirmed()).isTrue();
        EastMoneyIntradayPoint point = points.get(24);
        points.add(with(point, new BigDecimal("99"), point.averagePrice(), point.low()));
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 41)).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void refusesDuplicateAmountConflictsEvenWithUnchangedPrices() {
        List<EastMoneyIntradayPoint> points = pattern();
        EastMoneyIntradayPoint point = points.get(24);
        points.add(new EastMoneyIntradayPoint(point.symbol(), point.minute(), point.open(), point.close(),
                point.high(), point.low(), point.volume(), BigDecimal.ONE, point.averagePrice()));
        assertThat(evaluator.evaluate(points, DATE, DATE.atTime(13, 41)).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void reportsMissingVolumeAndAveragePriceWithoutInventingThem() {
        List<EastMoneyIntradayPoint> points = pattern();
        EastMoneyIntradayPoint point = points.get(24);
        points.set(24, with(point, null, point.averagePrice(), point.low()));
        assertThat(evaluate(points).status()).isEqualTo("UNAVAILABLE");
        points.set(24, with(point, point.volume(), null, point.low()));
        assertThat(evaluate(points).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void allowsZeroVolumeMinutesButCannotConfirmWithAZeroVolumeBaseline() {
        List<EastMoneyIntradayPoint> points = pattern();
        EastMoneyIntradayPoint point = points.get(24);
        points.set(24, with(point, BigDecimal.ZERO, point.averagePrice(), point.low()));
        assertThat(evaluate(points).confirmed()).isTrue();
        replaceBar(points, 0, "10.10", "10.14", "10.08", "0");
        replaceBar(points, 1, "10.12", "10.14", "10.08", "0");
        assertThat(evaluate(points).confirmed()).isFalse();
    }

    private ShortTermIntradayPullbackSignal evaluate(List<EastMoneyIntradayPoint> points) {
        LocalDateTime decision = points.get(points.size() - 1).minute().plusMinutes(1);
        return evaluator.evaluate(points, DATE, decision);
    }

    private List<EastMoneyIntradayPoint> pattern() {
        List<EastMoneyIntradayPoint> points = firstThreeBars();
        appendBar(points, "10.15", "10.20", "10.04", "180");
        return points;
    }

    private List<EastMoneyIntradayPoint> firstThreeBars() {
        List<EastMoneyIntradayPoint> points = new ArrayList<>();
        appendBar(points, "10.10", "10.14", "10.08", "100");
        appendBar(points, "10.12", "10.14", "10.08", "100");
        appendBar(points, "10.03", "10.10", "10.02", "60");
        return points;
    }

    private void appendBar(List<EastMoneyIntradayPoint> points, String close, String high, String low, String volume) {
        int start = points.size();
        for (int i = 1; i <= 10; i++) points.add(point(start + i, close, high, low, volume));
    }

    private void replaceBar(List<EastMoneyIntradayPoint> points, int bar, String close, String high, String low, String volume) {
        for (int i = 0; i < 10; i++) points.set(bar * 10 + i, point(bar * 10 + i + 1, close, high, low, volume));
    }

    private EastMoneyIntradayPoint point(int minute, String close, String high, String low, String volume) {
        return new EastMoneyIntradayPoint("600041", DATE.atTime(13, 0).plusMinutes(minute),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(volume), null, new BigDecimal("10.00"));
    }

    private EastMoneyIntradayPoint with(EastMoneyIntradayPoint point, BigDecimal volume, BigDecimal average, BigDecimal low) {
        return new EastMoneyIntradayPoint(point.symbol(), point.minute(), point.open(), point.close(),
                point.high(), low, volume, point.amount(), average);
    }
}

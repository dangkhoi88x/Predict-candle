package com.example.candles.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandleAggregatorTest {

    private static final Instant MIDNIGHT = Instant.parse("2026-03-15T00:00:00Z");

    private static CandleAggregator.Bar bar(Instant time, double o, double h, double l, double c, double v) {
        return new CandleAggregator.Bar(time, BigDecimal.valueOf(o), BigDecimal.valueOf(h),
                BigDecimal.valueOf(l), BigDecimal.valueOf(c), BigDecimal.valueOf(v));
    }

    /** {@code count} hourly bars from {@code from}, each one dollar higher than the last. */
    private static List<CandleAggregator.Bar> hours(Instant from, int count) {
        List<CandleAggregator.Bar> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double base = 100 + i;
            bars.add(bar(from.plus(Duration.ofHours(i)), base, base + 2, base - 2, base + 1, 10));
        }
        return bars;
    }

    private static double d(BigDecimal v) {
        return v.doubleValue();
    }

    @Test
    void fourHoursOfCandlesBecomeOneBarWithTheOuterOpenAndClose() {
        List<CandleAggregator.Bar> rolled = CandleAggregator.rollUp(hours(MIDNIGHT, 4), "4h");

        assertThat(rolled).hasSize(1);
        CandleAggregator.Bar bar = rolled.getFirst();
        assertThat(bar.time()).isEqualTo(MIDNIGHT);
        assertThat(d(bar.open())).isEqualTo(100);      // first hour's open
        assertThat(d(bar.close())).isEqualTo(104);     // last hour's close
        assertThat(d(bar.high())).isEqualTo(105);      // highest high across the four
        assertThat(d(bar.low())).isEqualTo(98);        // lowest low across the four
        assertThat(d(bar.volume())).isEqualTo(40);     // summed
    }

    /**
     * The mistake this class exists to avoid. Chunking the list in fours would put these six
     * hours into buckets of 4 + 2 counted from the start of the window; aligning to the clock
     * puts them into 00:00–04:00 and 04:00–08:00 no matter where the window began.
     */
    @Test
    void bucketsFollowTheClockRatherThanThePositionInTheList() {
        // Starts at 02:00, so the first bucket is already half over.
        List<CandleAggregator.Bar> rolled =
                CandleAggregator.rollUp(hours(MIDNIGHT.plus(Duration.ofHours(2)), 6), "4h");

        assertThat(rolled).hasSize(2);
        assertThat(rolled.get(0).time()).isEqualTo(MIDNIGHT);
        assertThat(rolled.get(1).time()).isEqualTo(MIDNIGHT.plus(Duration.ofHours(4)));
        // 02:00 and 03:00 only — a partial bucket, and it opens at the first candle it has.
        assertThat(d(rolled.get(0).volume())).isEqualTo(20);
        assertThat(d(rolled.get(1).volume())).isEqualTo(40);
    }

    /**
     * Same six hours, fetched as a longer window, must produce the same bars for the periods
     * they share. A chart that re-buckets itself every time the sync lands is unreadable.
     */
    @Test
    void thesameHoursGiveTheSameBarsHoweverManyWereFetched() {
        List<CandleAggregator.Bar> shortWindow = CandleAggregator.rollUp(hours(MIDNIGHT, 8), "4h");
        List<CandleAggregator.Bar> longWindow = CandleAggregator.rollUp(hours(MIDNIGHT, 12), "4h");

        assertThat(longWindow).hasSize(3);
        assertThat(longWindow.get(0)).isEqualTo(shortWindow.get(0));
        assertThat(longWindow.get(1)).isEqualTo(shortWindow.get(1));
    }

    @Test
    void aDayIsTwentyFourHoursAlignedToUtcMidnight() {
        List<CandleAggregator.Bar> rolled = CandleAggregator.rollUp(hours(MIDNIGHT, 30), "1d");

        assertThat(rolled).hasSize(2);
        assertThat(rolled.get(0).time()).isEqualTo(MIDNIGHT);
        assertThat(rolled.get(1).time()).isEqualTo(MIDNIGHT.plus(Duration.ofDays(1)));
        assertThat(d(rolled.get(0).volume())).isEqualTo(240);
        // The trailing six hours are a partial day and come back as one, not dropped.
        assertThat(d(rolled.get(1).volume())).isEqualTo(60);
    }

    @Test
    void aPartialFinalBucketIsKeptBecauseThatIsTheCandleStillForming() {
        List<CandleAggregator.Bar> rolled = CandleAggregator.rollUp(hours(MIDNIGHT, 5), "4h");

        assertThat(rolled).hasSize(2);
        assertThat(d(rolled.get(1).open())).isEqualTo(104);
        assertThat(d(rolled.get(1).close())).isEqualTo(105);
    }

    @Test
    void rollingUpToTheSourcesOwnTimeframeChangesNothing() {
        List<CandleAggregator.Bar> source = hours(MIDNIGHT, 5);
        assertThat(CandleAggregator.rollUp(source, "1h")).isEqualTo(source);
    }

    @Test
    void anEmptyWindowRollsUpToNothing() {
        assertThat(CandleAggregator.rollUp(List.of(), "4h")).isEmpty();
    }
}

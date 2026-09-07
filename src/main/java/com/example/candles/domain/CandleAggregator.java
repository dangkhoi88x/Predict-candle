package com.example.candles.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.candles.client.Timeframes;

/**
 * Rolls stored candles up into a longer timeframe.
 *
 * The app syncs and stores one timeframe only, so a 4h or 1d chart is folded out of the hourly
 * candles already on disk rather than fetched and stored again. Nothing new to sync, nothing new
 * to keep in step — the same reasoning that keeps streaks and portfolios derived.
 *
 * <h2>Buckets are aligned to the clock, not to the window</h2>
 *
 * Every bar's bucket comes from {@link Timeframes#currentPeriodStart}, which counts periods from
 * the epoch the way exchanges do. Chunking the list into groups of four instead would be simpler
 * and wrong: the grouping would depend on how many candles happened to be fetched, so a 4h
 * candle would silently cover 01:00–05:00 for one request and 02:00–06:00 for the next, and
 * every bar on the chart would shift each time the hourly sync landed.
 *
 * The final bucket is usually partial — the current 4h period with only an hour in it so far —
 * and is returned as it stands, because that is the candle a live chart is meant to show
 * forming. Pure over plain values so the folding can be tested without a database.
 */
public final class CandleAggregator {

    private CandleAggregator() {
    }

    /** One candle, in or out. {@code time} is the period's open time. */
    public record Bar(Instant time, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal close, BigDecimal volume) {
    }

    /**
     * @param source oldest first, all of one timeframe shorter than {@code timeframe}
     * @return one bar per period that {@code source} touches, oldest first
     */
    public static List<Bar> rollUp(List<Bar> source, String timeframe) {
        Map<Instant, Bar> buckets = new LinkedHashMap<>();

        for (Bar bar : source) {
            Instant start = Timeframes.currentPeriodStart(bar.time(), timeframe);
            Bar open = buckets.get(start);
            if (open == null) {
                // The period's open is the first candle in it, and stays that however many
                // more arrive — which is why the bucket is seeded rather than recomputed.
                buckets.put(start, new Bar(start, bar.open(), bar.high(), bar.low(),
                        bar.close(), bar.volume()));
                continue;
            }
            buckets.put(start, new Bar(
                    start,
                    open.open(),
                    open.high().max(bar.high()),
                    open.low().min(bar.low()),
                    bar.close(),
                    open.volume().add(bar.volume())));
        }

        return List.copyOf(new ArrayList<>(buckets.values()));
    }
}

package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.example.candles.client.CandleData;
import com.example.candles.client.PriceDataProvider;
import com.example.candles.client.Timeframes;

/**
 * Candles finer than the one timeframe this app stores.
 *
 * The sync keeps hourly candles and nothing else, so a 4h or 1d chart can be folded out of what
 * is on disk — but a 1m or 15m chart cannot. That detail was never recorded, and an hourly
 * candle cannot be taken apart into the sixty minutes that made it. These have to come from the
 * exchange, live, or not at all.
 *
 * They are deliberately **not** stored. A minute of history for four pairs is sixty times the
 * rows an hour is, for a chart nobody looks at twice, and it would need its own sync, its own
 * backfill and its own gaps to reason about. Fetching on demand keeps all of that out of the
 * schema.
 *
 * Cached briefly because every open terminal asks for the same window, and the exchange's rate
 * limit is shared by all of them. The TTL is a fraction of the timeframe, so a 1m chart is never
 * more than a few seconds stale while a 15m one is not refetched sixty times an hour for nothing.
 */
@Service
public class IntradayCandleService {

    /** Long enough to absorb a burst of viewers, short enough that a 1m chart still looks live. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(20);

    /** Binance pages at 1000; staying inside one page keeps this a single upstream call. */
    private static final int MAX_BARS = 500;

    private final PriceDataProvider priceDataProvider;
    private final Clock clock;
    private final Cache<String, List<CandleData>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .build();

    public IntradayCandleService(PriceDataProvider priceDataProvider, Clock clock) {
        this.priceDataProvider = priceDataProvider;
        this.clock = clock;
    }

    /**
     * The most recent {@code bars} candles of {@code timeframe}, oldest first.
     *
     * Empty when the exchange has nothing to say rather than throwing: the chart is context
     * around a trade, and losing it must not stop anyone trading.
     */
    public List<CandleData> recent(String symbol, String timeframe, int bars) {
        int wanted = Math.clamp(bars, 20, MAX_BARS);
        String key = symbol + "@" + timeframe + "@" + wanted;

        return cache.get(key, k -> {
            Instant now = clock.instant();
            // Asked for slightly more than needed and trimmed, because the first period in the
            // range is usually already partway through and comes back short.
            Instant from = now.minus(Timeframes.parse(timeframe).multipliedBy(wanted + 2L));
            try {
                // now + 1ms, not now: this deliberately includes the candle still forming, which
                // is the one a live chart is mostly about. CandleSyncService stops short of it
                // for the opposite reason — it must never store a candle that is still moving.
                List<CandleData> page = priceDataProvider.fetchCandles(
                        symbol, timeframe, from, now.plusMillis(1));
                return page.size() <= wanted ? page
                        : List.copyOf(page.subList(page.size() - wanted, page.size()));
            } catch (RuntimeException e) {
                return List.of();
            }
        });
    }

    /** Drops every cached window — the short TTL is right in production, wrong across tests. */
    public void evict() {
        cache.invalidateAll();
    }
}

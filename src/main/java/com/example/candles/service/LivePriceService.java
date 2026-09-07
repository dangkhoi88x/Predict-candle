package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.example.candles.client.CandleData;
import com.example.candles.client.PriceDataProvider;
import com.example.candles.config.CandlesProperties;
import com.example.candles.entity.Asset;

/**
 * What an asset costs right now, shared by every viewer.
 *
 * Deliberately separate from {@code LiveRoundService}'s own price read rather than extracted
 * from it: that one answers "the candle covering this round", validating its cache against the
 * round's open time, while this answers "the price at this moment" with no round in the
 * picture. Merging them would mean one of the two callers passing a round it does not have.
 *
 * Cached for the same short TTL the live round uses. Every open terminal asks on a timer and
 * they all want the same number, so without this the upstream sees one request per viewer per
 * tick instead of one per asset.
 */
@Service
public class LivePriceService {

    /** Far enough back that a quiet market still has a candle in range. */
    private static final Duration LOOKBACK = Duration.ofHours(6);

    private final PriceDataProvider priceDataProvider;
    private final CandlesProperties properties;
    private final Clock clock;
    private final Cache<String, BigDecimal> priceCache;

    public LivePriceService(PriceDataProvider priceDataProvider,
                            CandlesProperties properties,
                            Clock clock) {
        this.priceDataProvider = priceDataProvider;
        this.properties = properties;
        this.clock = clock;
        this.priceCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.live().priceCacheTtl())
                .build();
    }

    /**
     * Drops every cached price.
     *
     * The short TTL is right in production and wrong across tests sharing one JVM, where a test
     * that moves the market would otherwise be answered from the previous test's price. Same
     * reason {@code AdminStatsService} exposes one.
     */
    public void evict() {
        priceCache.invalidateAll();
    }

    /**
     * The latest close for {@code asset}, or null when the feed has nothing.
     *
     * Null rather than a stale fallback on purpose: a trade priced from a number nobody can
     * vouch for is worse than a trade that is refused, and the caller is in a position to say so
     * to the player.
     */
    public BigDecimal price(Asset asset) {
        return priceCache.get(asset.getSymbol(), symbol -> {
            Instant now = clock.instant();
            List<CandleData> page = priceDataProvider.fetchCandles(
                    symbol, properties.timeframe(), now.minus(LOOKBACK), now.plusMillis(1));
            return page.isEmpty() ? null : page.getLast().close();
        });
    }
}

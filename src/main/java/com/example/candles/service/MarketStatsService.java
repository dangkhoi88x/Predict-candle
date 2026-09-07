package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.repository.CandleRepository;

/**
 * The figures a market list shows beside a price: how far it has moved in a day and how much
 * has changed hands.
 *
 * Read from the stored candles rather than the live feed, because both are properties of a day
 * of settled history rather than of this second. The live price is a separate read for exactly
 * that reason — mixing them would make a "24h change" that jumps every two seconds.
 *
 * Cached for a minute. The inputs only move when the hourly sync lands, and the market list is
 * polled by every open terminal.
 */
@Service
public class MarketStatsService {

    private static final int HOURS = 24;
    private static final MathContext MC = MathContext.DECIMAL64;

    private final CandleRepository candles;
    private final CandlesProperties properties;
    private final Cache<String, Stats> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public MarketStatsService(CandleRepository candles, CandlesProperties properties) {
        this.candles = candles;
        this.properties = properties;
    }

    /** Nulls rather than zeroes when there is not a day of history yet — no move is not a flat move. */
    public record Stats(BigDecimal changePct, BigDecimal volume) {
        static final Stats UNKNOWN = new Stats(null, null);
    }

    public Stats stats(Asset asset) {
        return cache.get(asset.getSymbol(), symbol -> compute(asset));
    }

    public void evict() {
        cache.invalidateAll();
    }

    private Stats compute(Asset asset) {
        String timeframe = properties.timeframe();
        long total = candles.countByAssetAndTimeframe(asset, timeframe);
        if (total < HOURS) {
            return Stats.UNKNOWN;
        }

        List<Candle> day = candles.findWindow(asset.getId(), timeframe, (int) total - HOURS, HOURS);
        if (day.isEmpty()) {
            return Stats.UNKNOWN;
        }

        BigDecimal open = day.getFirst().getOpen();
        BigDecimal close = day.getLast().getClose();
        // Binance reports volume in the base asset — 10,200 BTC, not $10,200 — so it is priced
        // per candle to get turnover. Showing the raw figure with a currency symbol in front of
        // it would be off by the price of the asset, which on BTC is four orders of magnitude.
        BigDecimal volume = day.stream()
                .map(c -> c.getVolume().multiply(c.getClose(), MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal changePct = open.signum() == 0 ? null
                : close.subtract(open).divide(open, MC).multiply(BigDecimal.valueOf(100), MC);
        return new Stats(changePct, volume);
    }
}

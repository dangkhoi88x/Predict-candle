package com.example.candles.pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;

import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.repository.CandleRepository;

/**
 * Loads an asset's full candle history, shared by both pattern-example services (candlestick
 * and chart patterns). Cached briefly since the history only grows by one candle an hour.
 */
@Component
public class CandleHistoryLoader {

    private final CandleRepository candleRepository;
    private final Cache<String, List<Candle>> historyCache;

    public CandleHistoryLoader(CandleRepository candleRepository) {
        this.candleRepository = candleRepository;
        this.historyCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    public List<Candle> load(Asset asset, String timeframe) {
        String key = asset.getId() + ":" + timeframe;
        return historyCache.get(key, k -> {
            long total = candleRepository.countByAssetAndTimeframe(asset, timeframe);
            return candleRepository.findWindow(asset.getId(), timeframe, 0, (int) total);
        });
    }
}

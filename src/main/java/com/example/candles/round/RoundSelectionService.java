package com.example.candles.round;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.Asset;
import com.example.candles.domain.Candle;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks a random chart for practice mode: properties.round().visibleCandles() candles the
 * player sees, followed by up to properties.round().guessesPerChart() answer candles that get
 * revealed one at a time as the player keeps guessing. Avoids repeats and "dead" (near-flat)
 * charts where the outcome is just noise.
 */
@Service
public class RoundSelectionService {

    private final AssetRepository assetRepository;
    private final CandleRepository candleRepository;
    private final CandlesProperties properties;
    private final Cache<String, Boolean> recentlyServed;

    public RoundSelectionService(AssetRepository assetRepository,
                                  CandleRepository candleRepository,
                                  CandlesProperties properties) {
        this.assetRepository = assetRepository;
        this.candleRepository = candleRepository;
        this.properties = properties;
        this.recentlyServed = Caffeine.newBuilder()
                .expireAfterWrite(properties.round().repeatCacheTtl())
                .build();
    }

    public Asset resolveAsset(String assetSymbol) {
        return assetRepository.findBySymbol(assetSymbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + assetSymbol));
    }

    public RoundSelection selectRound(String assetSymbol) {
        Asset asset = resolveAsset(assetSymbol);
        String timeframe = properties.timeframe();
        int visibleCandles = properties.round().visibleCandles();
        int sessionSpan = visibleCandles + properties.round().guessesPerChart()
                + properties.round().revealCandlesAfterComplete();

        long total = candleRepository.countByAssetAndTimeframe(asset, timeframe);
        int maxStartIndex = (int) total - sessionSpan;
        if (maxStartIndex < 0) {
            throw new IllegalStateException("Not enough candle history for " + assetSymbol);
        }

        List<Candle> fallback = null;
        int fallbackStart = -1;
        for (int attempt = 0; attempt < properties.round().maxAttempts(); attempt++) {
            int startIndex = ThreadLocalRandom.current().nextInt(0, maxStartIndex + 1);
            String cacheKey = asset.getId() + ":" + startIndex;
            if (recentlyServed.getIfPresent(cacheKey) != null) {
                continue;
            }

            List<Candle> window = candleRepository.findWindow(asset.getId(), timeframe, startIndex, visibleCandles);
            if (window.size() < visibleCandles) {
                continue;
            }
            if (fallback == null) {
                fallback = window;
                fallbackStart = startIndex;
            }
            if (isLiveEnough(window)) {
                recentlyServed.put(cacheKey, Boolean.TRUE);
                return new RoundSelection(asset, timeframe, startIndex, window);
            }
        }

        if (fallback == null) {
            throw new IllegalStateException("Could not find a valid round for " + assetSymbol);
        }
        return new RoundSelection(asset, timeframe, fallbackStart, fallback);
    }

    /**
     * Fetches the answer candle for a given (1-based) guess number in an existing session,
     * i.e. the candle right after the visible window plus however many guesses already happened.
     */
    public Candle answerCandleAt(Asset asset, String timeframe, int startIndex, int guessNumber) {
        int index = startIndex + properties.round().visibleCandles() + (guessNumber - 1);
        List<Candle> window = candleRepository.findWindow(asset.getId(), timeframe, index, 1);
        if (window.isEmpty()) {
            throw new IllegalStateException("Answer candle no longer exists");
        }
        return window.get(0);
    }

    /**
     * Fetches the candles right after the last guess, purely for the post-session "here's
     * what actually happened next" reveal — not guessable, just closes the loop for players
     * curious how the chart continued.
     */
    public List<Candle> revealCandlesAfter(Asset asset, String timeframe, int startIndex, int guessesPerChart) {
        int index = startIndex + properties.round().visibleCandles() + guessesPerChart;
        int count = properties.round().revealCandlesAfterComplete();
        return candleRepository.findWindow(asset.getId(), timeframe, index, count);
    }

    private boolean isLiveEnough(List<Candle> window) {
        BigDecimal minRangePct = properties.round().minRangePct();
        BigDecimal totalRangePct = BigDecimal.ZERO;
        for (Candle candle : window) {
            BigDecimal range = candle.getHigh().subtract(candle.getLow());
            BigDecimal rangePct = range
                    .divide(candle.getClose(), MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100));
            totalRangePct = totalRangePct.add(rangePct);
        }
        BigDecimal avgRangePct = totalRangePct.divide(BigDecimal.valueOf(window.size()), MathContext.DECIMAL64);
        return avgRangePct.compareTo(minRangePct) >= 0;
    }
}

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
        Asset asset = assetRepository.findBySymbol(assetSymbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + assetSymbol));
        // Checked here rather than only in the picker: the picker is a list in a browser, and
        // the round endpoint takes whatever symbol it is given.
        if (!asset.isEnabled()) {
            throw new IllegalArgumentException("Cặp giao dịch này đang tạm tắt: " + asset.getSymbol());
        }
        return asset;
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

            /*
             * Fetch the answer candles alongside the visible ones. They were never inspected
             * before, so a chart could pass the liveness check on what the player sees and
             * still ask them to call the direction of a candle that closed where it opened.
             */
            int guessesPerChart = properties.round().guessesPerChart();
            List<Candle> span = candleRepository.findWindow(
                    asset.getId(), timeframe, startIndex, visibleCandles + guessesPerChart);
            if (span.size() < visibleCandles + guessesPerChart) {
                continue;
            }
            List<Candle> window = List.copyOf(span.subList(0, visibleCandles));
            List<Candle> answers = span.subList(visibleCandles, span.size());

            if (!answersAreDecisive(answers)) {
                continue;
            }
            /*
             * Only decisive charts are eligible to be the fallback. The two checks guard
             * different failures: a flat-looking chart is merely dull, while an indecisive
             * answer cannot be reasoned about at all — so running out of attempts may serve
             * a boring round, never an unanswerable one.
             */
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
        return candleAt(asset, timeframe,
                startIndex + properties.round().visibleCandles() + (guessNumber - 1));
    }

    /**
     * The round plus the candles either side of it, for the post-session context chart: the
     * run-up the player never saw, everything they played, and how it resolved afterwards.
     *
     * Clamped at the start of history, so the leading padding can come back short — the
     * caller works out where the played window sits from the returned {@code from}.
     */
    public ContextWindow contextWindow(Asset asset, String timeframe, int startIndex) {
        int padding = properties.round().contextPadding();
        int from = Math.max(0, startIndex - padding);
        int span = (startIndex - from)
                + properties.round().visibleCandles()
                + properties.round().guessesPerChart()
                + padding;
        return new ContextWindow(startIndex - from,
                candleRepository.findWindow(asset.getId(), timeframe, from, span));
    }

    /** {@code leading} is how many candles precede the played window — 0 at the very start of history. */
    public record ContextWindow(int leading, List<Candle> candles) {
    }

    /**
     * The candle at an absolute position in the asset's history — the same coordinate space
     * {@code startIndex} is expressed in. Used to date the chart once the session is over.
     */
    public Candle candleAt(Asset asset, String timeframe, int index) {
        List<Candle> window = candleRepository.findWindow(asset.getId(), timeframe, index, 1);
        if (window.isEmpty()) {
            throw new IllegalStateException("Candle at index " + index + " no longer exists");
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

    /**
     * Every answer candle must have a body big enough that up or down is a real call. One
     * indecisive candle is enough to spoil a chart, because each is asked as its own guess.
     */
    private boolean answersAreDecisive(List<Candle> answers) {
        BigDecimal minBodyPct = properties.round().minAnswerBodyPct();
        for (Candle candle : answers) {
            BigDecimal bodyPct = candle.getClose().subtract(candle.getOpen()).abs()
                    .divide(candle.getClose(), MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100));
            if (bodyPct.compareTo(minBodyPct) < 0) {
                return false;
            }
        }
        return true;
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

package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailySeed;
import com.example.candles.domain.RoundSelection;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;

/**
 * Picks a chart to play: properties.round().visibleCandles() candles the player sees, followed
 * by up to properties.round().guessesPerChart() answer candles that get revealed one at a time
 * as the player keeps guessing. Skips "dead" (near-flat) charts where the outcome is just noise.
 *
 * Two ways in, and the difference between them is where the randomness comes from.
 * {@link #selectRound} is practice: a fresh draw each time, avoiding charts served recently.
 * {@link #selectDailyRound} is the shared daily chart: the same draw for everyone, derived
 * from the date, repeats very much intended.
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
        long total = candleRepository.countByAssetAndTimeframe(asset, properties.timeframe());
        return pickWindow(asset, maxStartIndex(asset, total), ThreadLocalRandom.current(), true);
    }

    /**
     * The one chart everybody gets on a given UTC day — the same asset and the same window for
     * every player, every server and every reload, worked out from the date alone.
     *
     * Two things have to give way for that, and both are the point of this method existing
     * rather than a seed being passed to {@link #selectRound}:
     *
     * The repeat cache is ignored. It is per-instance and it expires, so honouring it would let
     * one server that had already served today's chart quietly hand out a different one — the
     * exact disagreement this is supposed to rule out. A daily round is meant to repeat.
     *
     * The window is drawn from the history that existed at midnight, not from all of it. See
     * {@link CandleRepository#countByAssetAndTimeframeAndOpenTimeLessThan}: a seeded draw
     * against a range the hourly sync keeps widening is not deterministic at all.
     *
     * The asset is chosen by position and includes disabled pairs, so today's chart cannot
     * change under a player because an admin turned a pair off at lunchtime. Adding a pair does
     * shift which asset future days land on — the list is the input, and a longer list is a
     * different input.
     */
    public RoundSelection selectDailyRound(LocalDate day) {
        Random random = new Random(DailySeed.forDay(day).value());

        List<Asset> assets = assetRepository.findAllByOrderByPositionAscSymbolAsc();
        if (assets.isEmpty()) {
            throw new IllegalStateException("No assets configured for a daily round");
        }

        /*
         * The seed names where to start looking, not what to settle for. A pair that has just
         * been added has no history behind it until the backfill catches up, and a daily round
         * that lands on one would be the whole site's only chart for the day — broken for
         * everybody, until midnight. Walking on from the seeded position keeps the choice
         * deterministic while making it survive a pair that cannot be played yet.
         */
        Instant midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        int first = random.nextInt(assets.size());
        for (int step = 0; step < assets.size(); step++) {
            Asset asset = assets.get((first + step) % assets.size());
            long total = candleRepository.countByAssetAndTimeframeAndOpenTimeLessThan(
                    asset, properties.timeframe(), midnight);
            if (hasEnoughHistory(total)) {
                return pickWindow(asset, maxStartIndex(asset, total), random, false);
            }
        }
        throw new IllegalStateException("No asset has enough candle history for a daily round");
    }

    private int sessionSpan() {
        return properties.round().visibleCandles() + properties.round().guessesPerChart()
                + properties.round().revealCandlesAfterComplete();
    }

    private boolean hasEnoughHistory(long totalCandles) {
        return totalCandles >= sessionSpan();
    }

    private int maxStartIndex(Asset asset, long totalCandles) {
        int maxStartIndex = (int) totalCandles - sessionSpan();
        if (maxStartIndex < 0) {
            throw new IllegalStateException("Not enough candle history for " + asset.getSymbol());
        }
        return maxStartIndex;
    }

    /**
     * Draws windows until one is worth playing. {@code avoidRepeats} is what separates a
     * practice round, which must not serve the same chart twice in a row, from a daily one,
     * which must serve the same chart to everyone.
     */
    private RoundSelection pickWindow(Asset asset, int maxStartIndex, Random random, boolean avoidRepeats) {
        String timeframe = properties.timeframe();
        int visibleCandles = properties.round().visibleCandles();

        List<Candle> fallback = null;
        int fallbackStart = -1;
        for (int attempt = 0; attempt < properties.round().maxAttempts(); attempt++) {
            int startIndex = random.nextInt(0, maxStartIndex + 1);
            String cacheKey = asset.getId() + ":" + startIndex;
            if (avoidRepeats && recentlyServed.getIfPresent(cacheKey) != null) {
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
                if (avoidRepeats) {
                    recentlyServed.put(cacheKey, Boolean.TRUE);
                }
                return new RoundSelection(asset, timeframe, startIndex, window);
            }
        }

        if (fallback == null) {
            throw new IllegalStateException("Could not find a valid round for " + asset.getSymbol());
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
     * Every answer candle of a round in one read, rather than {@link #answerCandleAt} once per
     * guess — used to redraw a session that is already over, where all of them are known.
     */
    public List<Candle> answerCandles(Asset asset, String timeframe, int startIndex, int guesses) {
        return candleRepository.findWindow(asset.getId(), timeframe,
                startIndex + properties.round().visibleCandles(), guesses);
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

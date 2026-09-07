package com.example.candles.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.HintLevel;
import com.example.candles.dto.response.RoundHints;
import com.example.candles.entity.Candle;
import com.example.candles.repository.CandleRepository;

/**
 * Builds the readings a struggling player has unlocked, over exactly the candles they can
 * currently see.
 *
 * Nothing here can reach the answer candle. The window it reads ends at the last candle already
 * revealed, so a hint is always a second look at what is on screen rather than a peek past it —
 * which is what keeps this a difficulty dial instead of a way out of the round.
 */
@Service
public class RoundHintService {

    /**
     * Short enough to still bend with the last few candles on a twenty-candle window. A longer
     * average would be flatter and calmer, which is the opposite of useful to someone trying to
     * call the very next candle.
     */
    private static final int MOVING_AVERAGE_PERIOD = 5;

    private final CandleRepository candles;
    private final CandlesProperties properties;
    private final RoundPatternScanner patternScanner;

    public RoundHintService(CandleRepository candles,
                            CandlesProperties properties,
                            RoundPatternScanner patternScanner) {
        this.candles = candles;
        this.properties = properties;
        this.patternScanner = patternScanner;
    }

    /**
     * @param guessNumber the guess the hints are for (1-based), which fixes how many answer
     *                    candles have been revealed
     * @param misses      how many guesses on this chart were wrong so far
     */
    public RoundHints hintsFor(Long assetId, String timeframe, int startIndex,
                               int guessNumber, int misses) {
        HintLevel level = HintLevel.forMisses(misses);
        if (!level.any()) {
            return RoundHints.NONE;
        }

        int seen = properties.round().visibleCandles() + (guessNumber - 1);
        List<Candle> window = candles.findWindow(assetId, timeframe, startIndex, seen);

        return new RoundHints(
                level.volume() ? window.stream().map(Candle::getVolume).toList() : null,
                level.movingAverage() ? movingAverage(window) : null,
                level.pattern() ? firstPatternIn(window) : null);
    }

    /** Nulls until a full period is behind the point, so the client can start the line late. */
    private List<BigDecimal> movingAverage(List<Candle> window) {
        List<BigDecimal> series = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            if (i + 1 < MOVING_AVERAGE_PERIOD) {
                series.add(null);
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i + 1 - MOVING_AVERAGE_PERIOD; j <= i; j++) {
                sum = sum.add(window.get(j).getClose());
            }
            series.add(sum.divide(BigDecimal.valueOf(MOVING_AVERAGE_PERIOD), MathContext.DECIMAL64));
        }
        return series;
    }

    /**
     * The latest pattern that completes inside what the player can see. Latest rather than
     * strongest: the candles nearest the one they are being asked about are the ones the hint
     * is about, and {@code scan} already returns hits in that order.
     */
    private String firstPatternIn(List<Candle> window) {
        List<RoundPatternScanner.PatternHit> hits = patternScanner.scan(window, 0, window.size());
        return hits.isEmpty() ? null : hits.getLast().patternId();
    }
}

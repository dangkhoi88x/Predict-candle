package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The extra readings unlocked for the guess the player is about to make.
 *
 * Every list is aligned with the candles they can currently see — the visible window plus the
 * answer candles revealed so far — so the client can draw them straight against the chart
 * without working out an offset. A locked hint is null rather than empty: nothing to draw and
 * a zero-length series mean different things to a renderer.
 *
 * {@code movingAverage} carries nulls for the leading candles that have no full period behind
 * them yet. {@code patternId} names a candlestick pattern in the candles already shown, never
 * anything about the answer candle — a hint that gave away the answer would end the round
 * rather than help with it. The client resolves the id to a name through
 * {@code CandlePatterns.nameOf}, the same way the end-of-round marks do.
 */
public record RoundHints(
        List<BigDecimal> volumes,
        List<BigDecimal> movingAverage,
        String patternId
) {
    public static final RoundHints NONE = new RoundHints(null, null, null);
}

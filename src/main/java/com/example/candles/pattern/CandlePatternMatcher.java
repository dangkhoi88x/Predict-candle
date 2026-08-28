package com.example.candles.pattern;

import com.example.candles.domain.Candle;

import java.util.List;

/**
 * Tests whether a fixed-size, oldest-to-newest window of candles exhibits a given pattern.
 * The last candle in the window is the pattern's defining candle.
 */
@FunctionalInterface
public interface CandlePatternMatcher {
    boolean matches(List<Candle> window);
}

package com.example.candles.pattern;

import java.util.List;

import com.example.candles.entity.Candle;

/**
 * Tests whether a fixed-size, oldest-to-newest window of candles exhibits a given pattern.
 * The last candle in the window is the pattern's defining candle.
 */
@FunctionalInterface
public interface CandlePatternMatcher {
    boolean matches(List<Candle> window);
}

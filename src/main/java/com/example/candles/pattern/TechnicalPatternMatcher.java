package com.example.candles.pattern;

import java.util.List;
import java.util.Optional;

import com.example.candles.entity.Candle;

/**
 * Tests whether the swing-point sequence starting at {@code pivots.get(pivotIdx)} forms a
 * given chart pattern, followed by a confirming breakout. Returns the matched candle-index
 * range ({@code [start, end)}, oldest-to-newest) if found.
 */
@FunctionalInterface
public interface TechnicalPatternMatcher {
    Optional<int[]> match(List<Candle> candles, List<SwingPoint> pivots, int pivotIdx);
}

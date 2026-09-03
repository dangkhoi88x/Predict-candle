package com.example.candles.pattern;

import java.time.Instant;
import java.util.List;

import com.example.candles.entity.Candle;

/**
 * A real occurrence of a pattern found in an asset's history: the candle slice to render
 * (context + the matching candles) and which sub-range within it is the pattern itself.
 */
public record PatternExample(
        String asset,
        String timeframe,
        Instant occurredAt,
        List<Candle> candles,
        int patternStartIndex,
        int patternLength
) {
}

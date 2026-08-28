package com.example.candles.api;

import java.time.Instant;
import java.util.List;

public record PatternExampleResponse(
        String asset,
        String timeframe,
        Instant occurredAt,
        List<CandleDto> candles,
        int patternStartIndex,
        int patternLength
) {
}

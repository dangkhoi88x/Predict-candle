package com.example.candles.api;

import java.util.List;

public record RoundResponse(
        String asset,
        String timeframe,
        List<CandleDto> candles,
        int totalGuesses,
        String roundToken
) {
}

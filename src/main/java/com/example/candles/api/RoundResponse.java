package com.example.candles.api;

import java.util.List;

public record RoundResponse(
        String asset,
        String timeframe,
        List<CandleDto> candles,
        int totalGuesses,
        /** Seconds on the countdown for each guess; the server allows a little more than this. */
        int guessSeconds,
        String roundToken
) {
}

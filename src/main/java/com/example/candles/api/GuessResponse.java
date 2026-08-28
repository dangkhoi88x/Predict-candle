package com.example.candles.api;

import java.util.List;

public record GuessResponse(
        boolean correct,
        String actualDirection,
        CandleDto actualCandle,
        int guessNumber,
        int totalGuesses,
        boolean sessionComplete,
        String nextRoundToken,
        List<CandleDto> revealCandles
) {
}

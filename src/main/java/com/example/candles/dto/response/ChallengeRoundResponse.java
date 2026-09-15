package com.example.candles.dto.response;

import java.util.List;

/**
 * A challenge chart for the daily board to draw. Named field for field like
 * {@code DailyRoundResponse} where they mean the same thing, because daily.js plays both.
 *
 * @param mine      the caller made this challenge: no token, the chart is shown finished, and
 *                  only the results are for them — they have seen its answers
 * @param finishers signed-in players who have played it through, best first; anonymous play is
 *                  never recorded, so it is never listed
 */
public record ChallengeRoundResponse(
        String id,
        String asset,
        String timeframe,
        List<CandleDto> candles,
        int totalGuesses,
        int guessSeconds,
        String roundToken,
        int guessesMade,
        boolean completed,
        List<DailyRoundResponse.Answer> answers,
        List<CandleDto> resolvedCandles,
        RoundHints hints,
        String creatorName,
        int creatorCorrect,
        boolean mine,
        List<Finisher> finishers
) {

    /** {@code you} marks the caller's own row. */
    public record Finisher(String displayName, int correct, int total, boolean you) {
    }
}

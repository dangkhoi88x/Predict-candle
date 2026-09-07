package com.example.candles.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Today's challenge, and how far the caller already got through it.
 *
 * One response serves both states on purpose. A player who has finished today needs the chart,
 * their answers and the countdown to tomorrow; a player who has not needs the chart and a
 * token. Splitting that into two endpoints would make the client ask which one to call before
 * it knows the answer.
 *
 * {@code roundToken} is null exactly when there is nothing left to play — finished today, or
 * mid-session with the next token carried in the guess response instead.
 *
 * Anonymous callers get the chart and a token but no {@code streak} and no answers: nothing is
 * recorded for them, so there is nothing to read back. The one-attempt rule is enforced by the
 * recorded rows, so signed out it is only as strong as the browser — which is the same bargain
 * practice already makes, and the alternative is putting a wallet in front of the first thing a
 * visitor sees.
 */
public record DailyRoundResponse(
        LocalDate day,
        long roundNumber,
        String asset,
        String timeframe,
        List<CandleDto> candles,
        int totalGuesses,
        int guessSeconds,
        String roundToken,
        int guessesMade,
        boolean completed,
        List<Answer> answers,
        List<CandleDto> resolvedCandles,
        Streak streak,
        Instant nextRoundAt
) {
    /** One guess already made. {@code guessed} is null for one the countdown ate. */
    public record Answer(int guessNumber, String guessed, String actual, boolean correct) {
    }

    /**
     * Days in a row the daily was played — not the profile's day streak, which any practice
     * round keeps alive. This one only counts days the challenge itself was played, so it can
     * break while the other holds.
     */
    public record Streak(int current, int best, long daysPlayed) {
    }
}

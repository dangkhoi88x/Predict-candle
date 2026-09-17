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
        /** Unlocked by misses already recorded today — a resumed session keeps what it earned. */
        RoundHints hints,
        Streak streak,
        Instant nextRoundAt,
        /** How everyone else did on this chart. Null until the caller has finished it, and null
            while too few have played for a percentage to mean anything. */
        Community community
) {
    /** One guess already made. {@code guessed} is null for one the countdown ate. */
    public record Answer(int guessNumber, String guessed, String actual, boolean correct) {
    }

    /**
     * The crowd's answer to the same chart, one entry per candle asked.
     *
     * <b>Sent only once the caller has finished.</b> Before that it is a hint — "62% called this
     * one LONG" is most of an answer — and the same rule the context chart follows for the same
     * reason.
     *
     * <b>It does not touch the score.</b> A chart's difficulty moves as more people play it, so
     * paying a bonus for it would quietly rewrite yesterday's score and, with it, a rank the
     * player already saw. Difficulty is shown, not scored.
     *
     * @param minPlayers the floor below which nothing is sent: a rate from four people is not a
     *                   community, it is four people
     */
    public record Community(int minPlayers, List<GuessRate> guesses) {
    }

    /** {@code players} answered this candle of the chart; {@code correct} of them called it right. */
    public record GuessRate(int guessNumber, long players, long correct) {
    }

    /**
     * Days in a row the daily was played — not the profile's day streak, which any practice
     * round keeps alive. This one only counts days the challenge itself was played, so it can
     * break while the other holds.
     */
    public record Streak(int current, int best, long daysPlayed) {
    }
}

package com.example.candles.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * One account, in enough detail to answer "what has this person actually been doing".
 *
 * The list above it carries two numbers per account and no way to see behind them, so a
 * suspicion about somebody — a total that grew impossibly fast, an accuracy that does not
 * happen — had nowhere to go. Every figure here is read from the same rows the game scores on;
 * nothing is stored for this page and nothing can be edited from it.
 */
public record AdminPlayerDetail(PlayerSummary account, Instant createdAt,
                                 List<ModeTally> modes, List<AssetTally> assets,
                                 LiveTally live, Legacy legacy,
                                 List<Guess> recentGuesses, List<LiveCall> recentLiveCalls) {

    /** How the account's practice/daily/archive history divides up. */
    public record ModeTally(String mode, long guesses, long correct) {
    }

    public record AssetTally(String symbol, long guesses, long correct) {
    }

    /**
     * {@code settled} is smaller than {@code calls} whenever a round the player called is still
     * running or never got its candle. Accuracy on live calls has to be read against
     * {@code settled}, not {@code calls}, which is why both are here.
     */
    public record LiveTally(long calls, long settled, long correct) {
    }

    /**
     * The browser tally folded in at first sign-in, shown separately and never added to
     * anything. Every figure in it is client-supplied — the leaderboard refuses to rank on it
     * for exactly that reason — so an admin looking at an account that seems too good needs to
     * be able to see whether the numbers were typed in rather than played.
     *
     * Null on an account that never imported one.
     */
    public record Legacy(long guesses, long correct, long score, int bestStreak, Instant importedAt) {
    }

    /**
     * One recorded guess. {@code guessedDirection} is null on a guess that timed out, which is
     * the shape the rest of the app already handles: it counts against the player and belongs
     * to neither side.
     */
    public record Guess(Instant createdAt, String mode, String symbol, int guessNumber,
                         String guessedDirection, String actualDirection, boolean correct) {
    }

    /** {@code correct} is null while the round has no candle to settle it. */
    public record LiveCall(Instant openTime, Instant createdAt, String symbol,
                            String direction, Boolean correct) {
    }
}

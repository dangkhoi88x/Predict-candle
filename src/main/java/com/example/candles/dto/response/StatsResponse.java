package com.example.candles.dto.response;

import java.util.List;

/**
 * The top-level numbers are what the player sees: everything they have done, including the
 * tally carried over from before they had an account.
 *
 * {@code recorded} is the subset the server watched happen. Imported figures come from the
 * client and cannot be checked, so anything that ranks one player against another has to read
 * that field instead — the combined view is for the player's own profile only.
 *
 * Accuracy is left for the caller to divide out; rounding it here and again in the UI is how
 * two places end up disagreeing about the same number.
 */
public record StatsResponse(
        long total,
        long correct,
        int bestStreak,
        int currentStreak,
        long score,
        Recorded recorded,
        boolean legacyImported,
        List<AssetTally> byAsset,
        List<RecentGuess> recent
) {
    /** Verified totals: guesses this server saw and scored itself. */
    public record Recorded(long total, long correct, int bestStreak, int currentStreak, long score) {
    }

    public record AssetTally(String symbol, long total, long correct) {
    }

    /** Only guesses the server recorded appear here — an imported tally has no detail. */
    /** {@code guessed} is null for a guess the countdown ate — no answer, not a wrong one. */
    public record RecentGuess(String symbol, String guessed, String actual, boolean correct,
                              java.time.Instant at) {
    }
}

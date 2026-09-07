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
        DayStreak dayStreak,
        /** The whole catalogue, earned and unearned — an unearned badge carries its progress. */
        List<Badge> achievements,
        boolean legacyImported,
        List<AssetTally> byAsset,
        List<RecentGuess> recent
) {
    /** Verified totals: guesses this server saw and scored itself. */
    public record Recorded(long total, long correct, int bestStreak, int currentStreak, long score) {
    }

    /**
     * Days shown up, not calls got right — {@code bestStreak} above is the other streak, and
     * naming this one anything shorter would put two different "streak" numbers on the same
     * screen with nothing to tell them apart. Never folds in imported figures: the carried-over
     * tally is four totals with no dates on it, so it cannot say which days were played.
     */
    public record DayStreak(int current, int best, long daysPlayed, boolean playedToday) {
    }

    /**
     * One badge as the profile draws it. A copy of {@code Achievement.Progress} rather than that
     * record itself, because {@code domain/} is where things that never leave the server live —
     * the rule that decides a badge and the shape sent to a browser are allowed to move apart.
     */
    public record Badge(String id, String name, String description,
                        long progress, long target, boolean earned) {
    }

    public record AssetTally(String symbol, long total, long correct) {
    }

    /** Only guesses the server recorded appear here — an imported tally has no detail. */
    /** {@code guessed} is null for a guess the countdown ate — no answer, not a wrong one. */
    public record RecentGuess(String symbol, String guessed, String actual, boolean correct,
                              java.time.Instant at) {
    }
}

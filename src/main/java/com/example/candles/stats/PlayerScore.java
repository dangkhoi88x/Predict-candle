package com.example.candles.stats;

import java.util.List;

/**
 * The scoring rule, in the one place that is allowed to define it.
 *
 * It used to live only in app.js, which was fine while a score was a private number in one
 * browser. A leaderboard cannot trust a score the client computed and submitted, so the rule
 * moves here and the client will read the result instead of keeping its own tally.
 *
 * Deliberately a pure function over the ordered correct/incorrect flags: no repository, no
 * entities, nothing to mock. The awkward part of player stats is the streak, and this is the
 * shape that makes it testable.
 */
public record PlayerScore(long total, long correct, int bestStreak, int currentStreak, long score) {

    /** Matches app.js: 10 for a correct guess, +2 per prior streak step, capped at +20. */
    private static final int BASE_POINTS = 10;
    private static final int STREAK_BONUS = 2;
    private static final int MAX_BONUS_STEPS = 10;

    public static PlayerScore of(List<Boolean> resultsInPlayOrder) {
        long total = 0, correct = 0, score = 0;
        int streak = 0, best = 0;

        for (Boolean ok : resultsInPlayOrder) {
            total++;
            if (Boolean.TRUE.equals(ok)) {
                correct++;
                streak++;
                best = Math.max(best, streak);
                score += BASE_POINTS + (long) Math.min(streak - 1, MAX_BONUS_STEPS) * STREAK_BONUS;
            } else {
                streak = 0;
            }
        }
        return new PlayerScore(total, correct, best, streak, score);
    }
}

package com.example.candles.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * How many days running the player has shown up — a different number from the streak
 * {@link PlayerScore} counts, and the two are easy to confuse. That one is consecutive correct
 * calls and resets on a miss; this one is consecutive calendar days with at least one call on
 * them, and a miss does not touch it. Playing badly does not cost you a day.
 *
 * Nothing stores it. Days come out of the timestamps already on {@code guess_results} and
 * {@code live_predictions}, the same way a live round is named from the clock rather than kept
 * in a table — so it cannot drift out of step with the history it describes, and deleting a
 * player's results takes their streak with them without a second write.
 *
 * Days are UTC, matching where every other calendar boundary in this app is drawn.
 *
 * {@code current} survives a day that has not been played yet: it counts back from today, or
 * from yesterday when that is the last day played. Without that grace every streak on the site
 * would read zero from midnight UTC until its owner next opened the game — a player who has
 * done nothing wrong should not watch their streak break while they sleep. It breaks only once
 * a whole day has gone by unplayed.
 */
public record PlayStreak(int current, int best, long daysPlayed, boolean playedToday) {

    /** {@code daysDescending} must be distinct and newest first — what the SQL {@code distinct} yields. */
    public static PlayStreak of(List<LocalDate> daysDescending, LocalDate today) {
        if (daysDescending.isEmpty()) {
            return new PlayStreak(0, 0, 0, false);
        }

        LocalDate newest = daysDescending.get(0);
        boolean playedToday = newest.equals(today);
        boolean alive = playedToday || newest.equals(today.minusDays(1));

        int best = 1;
        int run = 1;
        int leading = 1;
        for (int i = 1; i < daysDescending.size(); i++) {
            boolean consecutive = daysDescending.get(i - 1).minusDays(1).equals(daysDescending.get(i));
            run = consecutive ? run + 1 : 1;
            best = Math.max(best, run);
            // The run containing the newest day is the one still going; it stops growing at
            // the first gap, while `run` carries on measuring the older blocks for `best`.
            if (consecutive && leading == i) {
                leading = i + 1;
            }
        }

        return new PlayStreak(alive ? leading : 0, best, daysDescending.size(), playedToday);
    }
}

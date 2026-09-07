package com.example.candles.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * The baseline: does anyone come back, and do they play more than once when they do.
 *
 * Counts only, never rates — the caller divides. Two places rounding the same ratio is how a
 * dashboard ends up disagreeing with itself, which the profile response already says about
 * accuracy and is doubly true of a number whose whole job is to be compared against itself
 * before and after a release.
 *
 * Every figure excludes admin accounts, and days are UTC, like every other calendar boundary
 * here.
 */
public record AdminRetention(
        LocalDate from,
        LocalDate to,
        Summary summary,
        List<Cohort> cohorts,
        List<Daily> daily
) {
    /**
     * Cohorts folded into one pair of fractions, over the cohorts old enough to have an answer.
     *
     * That maturity filter is the whole difficulty of this record. A cohort that first played
     * yesterday cannot have come back within seven days yet, and counting it as a cohort that
     * did not come back drags the average toward zero — the site would appear to lose retention
     * every time it gained a new player. So each window has its own denominator:
     * {@code nextDayEligible} is cohorts at least a day old, {@code withinWeekEligible} at least
     * seven, and both are usually smaller than {@code newPlayers}.
     *
     * {@code plays / activePlayerDays} is calls per player on a day they played. The
     * denominator counts a player once per active day on purpose — dividing by distinct players
     * instead would answer "calls per player over the whole window", a different number that
     * grows simply because the window is long.
     */
    public record Summary(
            long newPlayers,
            long nextDayEligible,
            long returnedNextDay,
            long withinWeekEligible,
            long returnedWithinWeek,
            long plays,
            long activePlayerDays
    ) {
    }

    /**
     * One day's intake. {@code mature} flags say whether that row's return counts are final or
     * still being earned — a row with {@code withinWeekMature} false is not a bad week, it is an
     * unfinished one, and drawing the two the same way is the one mistake this pane can make.
     */
    public record Cohort(
            LocalDate day,
            long newPlayers,
            long returnedNextDay,
            long returnedWithinWeek,
            boolean nextDayMature,
            boolean withinWeekMature
    ) {
    }

    /** {@code plays} divided by {@code activePlayers} is calls per active player that day. */
    public record Daily(LocalDate day, long plays, long activePlayers) {
    }
}

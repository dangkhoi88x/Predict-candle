package com.example.candles.admin;

import java.time.Instant;
import java.util.List;

/**
 * The time series behind the admin overview.
 *
 * {@code /api/admin/ops} already answers "what are the numbers right now"; this answers "and
 * how did they get there", which is the only thing a chart can draw. The two are separate
 * calls because the snapshot is cheap and this is not — it groups the whole guess history.
 *
 * Three series rather than one: the main chart follows the range the reader picked, while the
 * accuracy line is always twelve weeks and the active-player bars always fourteen days. Those
 * two windows are part of what those panels mean, so the client does not get to choose them.
 */
public record AdminStats(
        String range,
        Instant generatedAt,
        List<Bucket> buckets,
        List<Bucket> daily,
        List<Bucket> weekly,
        List<AccountPoint> accounts,
        Totals totals,
        Deltas deltas
) {

    /**
     * One column of the chart. {@code start} is the bucket's first instant in UTC, and the
     * client turns it into whatever label the range calls for — a weekday, a month, a year.
     *
     * The two totals are deliberately both here, because they answer different questions and
     * the page asks both:
     *
     * <ul>
     *   <li>{@code guesses} — every guess put to a player, a timed-out one included. This is
     *       the accuracy denominator, and it is the one the rest of the app already uses:
     *       {@code PlayerScore} counts a guess nobody answered against the player.</li>
     *   <li>{@code answered} — long + short, the part that has a direction. This is the
     *       chart column's height, because the chart's legend says SHORT and LONG and drawing
     *       a column taller than the two stacks that make it up would be a lie.</li>
     * </ul>
     *
     * Reading correct/answered instead of correct/guesses reports accuracy about nine points
     * high on the current data, so the distinction is not academic.
     */
    public record Bucket(Instant start, long guesses, long longCount, long shortCount,
                          long answered, long correct, long activePlayers) {
    }

    /** One day of the account curve: how many accounts existed at its end, and how many arrived. */
    public record AccountPoint(Instant start, long added, long total) {
    }

    /**
     * Totals over the twelve weeks the accuracy panel plots, not over all history — the panel
     * says "12 tuần" beside them, and a headline computed over a different window than the
     * line beneath it is two numbers claiming to be one. Also keeps this endpoint off a
     * full-table scan.
     *
     * {@code accuracy} is null when nothing was guessed, which is not the same as 0%.
     */
    public record Totals(long guesses, Double accuracy, long activePlayersToday, long accounts) {
    }

    /**
     * Fractional change against the period before, so 0.94 is "94% more than yesterday".
     * Null where the earlier period is empty: there is no ratio to a zero, and drawing one as
     * a rise of 100% would be an invention.
     *
     * Each one measures the same quantity as the KPI it sits under — {@code players} is the
     * growth of the account total, not the churn in weekly actives, because the number above
     * it is a running total of accounts.
     */
    public record Deltas(Double guessesToday, Double accuracy, Double players, Double guessesWeek) {
    }
}

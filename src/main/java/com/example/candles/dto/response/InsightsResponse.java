package com.example.candles.dto.response;

import java.util.List;

/**
 * The profile's "habits" section — the outward copy of {@code PlayerInsights.Summary}. Counts, never
 * rates, like the retention pane: the page divides once, so two places cannot round differently.
 *
 * @param analysed  recorded calls read, newest first, up to {@code window}
 * @param minSample answered calls needed before any finding is made
 * @param findings  most pronounced first; each names a section below rather than repeating its numbers
 */
public record InsightsResponse(
        int analysed,
        int window,
        int minSample,
        boolean enough,
        long timedOut,
        Calls calls,
        List<TrendBucket> trends,
        List<SessionBucket> sessions,
        List<Finding> findings
) {

    public record Calls(long longCalls, long shortCalls, long marketUp, long marketDown,
                        long correctLong, long correctShort, long correctWhenUp, long correctWhenDown) {
    }

    /** {@code trend} is RISING / FALLING / FLAT: what the chart had just done before the call. */
    public record TrendBucket(String trend, long total, long correct, long longCalls) {
    }

    /** {@code session} is NIGHT / MORNING / AFTERNOON / EVENING in Vietnam time. */
    public record SessionBucket(String session, long total, long correct, long longCalls) {
    }

    /** {@code kind} is LONG_BIAS / SHORT_BIAS / WEAK_TREND / WEAK_SESSION / TIMEOUTS. */
    public record Finding(String kind, String key, int gapPoints) {
    }
}

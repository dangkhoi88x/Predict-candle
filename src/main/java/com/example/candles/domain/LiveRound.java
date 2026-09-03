package com.example.candles.domain;

import com.example.candles.client.Timeframes;

import java.time.Duration;
import java.time.Instant;

/**
 * One live round, which is one real candle.
 *
 * Nothing stores a round. The clock names it — a round *is* the candle open at
 * {@code openTime} — so two servers, a page reload and a player who joins halfway all agree on
 * which round is running without asking anyone. Same reasoning as the practice round's stateless
 * token, one step further: here there is not even a token, because the round is public.
 *
 * The number exists only so players can say "round 21". It counts periods from a fixed epoch, so
 * it never restarts, never depends on when the app was deployed, and cannot disagree between
 * environments.
 */
public record LiveRound(long number, Instant openTime, Instant lockAt, Instant closeAt) {

    /** Chosen once and never moved: shifting it renumbers every round that was ever played. */
    public static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    public static LiveRound at(Instant now, String timeframe, Duration lockBefore) {
        Duration period = Timeframes.parse(timeframe);
        Instant open = Timeframes.currentPeriodStart(now, timeframe);
        Instant close = open.plus(period);
        /* A lock window longer than the round would put the gate before the round opened, which
           reads as "closed" from the first second. Clamping keeps a misconfiguration merely
           useless rather than confusing. */
        Duration lock = lockBefore.compareTo(period) >= 0 ? period.dividedBy(2) : lockBefore;
        return new LiveRound(numberOf(open, period), open, close.minus(lock), close);
    }

    /** The round that ran immediately before this one — the newest that can already be settled. */
    public LiveRound previous(String timeframe, Duration lockBefore) {
        return at(openTime.minusMillis(1), timeframe, lockBefore);
    }

    private static long numberOf(Instant open, Duration period) {
        return Duration.between(EPOCH, open).toMillis() / period.toMillis() + 1;
    }

    public boolean isLocked(Instant now) {
        return !now.isBefore(lockAt);
    }

    public boolean hasClosed(Instant now) {
        return !now.isBefore(closeAt);
    }

    /** Milliseconds until picks stop being accepted; zero once they have. */
    public long millisUntilLock(Instant now) {
        return Math.max(0, Duration.between(now, lockAt).toMillis());
    }

    public long millisUntilClose(Instant now) {
        return Math.max(0, Duration.between(now, closeAt).toMillis());
    }
}

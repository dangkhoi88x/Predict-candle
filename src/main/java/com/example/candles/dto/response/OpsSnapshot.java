package com.example.candles.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * What the operations panel shows: enough to answer "is this thing healthy" without opening a
 * log or a psql prompt.
 */
public record OpsSnapshot(
        List<AssetHealth> assets,
        Schema schema,
        GameSettings settings,
        Activity activity,
        List<RecentError> recentErrors,
        Instant generatedAt
) {

    /**
     * One failure, or a run of the same failure folded together — {@code count} of them between
     * {@code firstAt} and {@code lastAt}. Newest first in the list. See {@code RecentErrors}.
     */
    public record RecentError(String source, String where, String summary, Instant firstAt,
                              Instant lastAt, int count) {
    }

    /**
     * {@code lagMinutes} is the number that matters. A stalled ingest looks exactly like a
     * healthy one from a candle count — the count is large either way — and shows up here as
     * a lag that keeps growing past the timeframe.
     */
    public record AssetHealth(String symbol, String name, String timeframe, long candles,
                               Instant firstCandle, Instant latestCandle, Long lagMinutes,
                               boolean stale) {
    }

    /** {@code pendingMigrations} being anything but zero means the app is running ahead of its schema. */
    public record Schema(String currentVersion, int appliedMigrations, int pendingMigrations,
                          String ddlAuto) {
    }

    /**
     * {@code priceSource} is {@code binance} or {@code okx}. It is on the panel because the two
     * disagree by a few dollars on any given candle, and an admin comparing a chart here against
     * Binance should not have to guess why.
     */
    public record GameSettings(String timeframe, int visibleCandles, int guessesPerChart,
                                int revealCandles, int contextPadding, int guessSeconds,
                                int roundsPerMinute, int guessesPerMinute, String priceSource) {
    }

    /**
     * {@code liveCallsToday} counts a call the moment it is placed; {@code liveSettledToday}
     * and {@code liveCorrectToday} only count calls whose candle has since closed, so the panel
     * can show "how many were called" and "how many of those we know the answer to" as two
     * different numbers rather than treating a call still in flight as either wrong or missing.
     */
    public record Activity(long players, long admins, long guessesToday, long correctToday,
                            long guessesWeek, long blogPosts, long publishedBlogPosts,
                            long contentItems, long liveCallsToday, long liveSettledToday,
                            long liveCorrectToday, long liveCallsWeek) {
    }
}

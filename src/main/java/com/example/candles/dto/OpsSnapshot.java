package com.example.candles.dto;

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
        Instant generatedAt
) {

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

    public record GameSettings(String timeframe, int visibleCandles, int guessesPerChart,
                                int revealCandles, int contextPadding, int guessSeconds,
                                int roundsPerMinute, int guessesPerMinute) {
    }

    public record Activity(long players, long admins, long guessesToday, long correctToday,
                            long guessesWeek, long blogPosts, long publishedBlogPosts,
                            long contentItems) {
    }
}

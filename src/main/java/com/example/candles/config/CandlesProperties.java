package com.example.candles.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ConfigurationProperties(prefix = "candles")
public record CandlesProperties(
        Binance binance,
        String timeframe,
        List<AssetConfig> assets,
        Backfill backfill,
        Round round,
        Jwt jwt
) {

    public record Binance(String baseUrl) {
    }

    public record AssetConfig(String symbol, String name) {
    }

    public record Backfill(Instant start) {
    }

    /**
     * @param minRangePct     average high-low range the visible window must show, as a
     *                        percentage, so players are not asked to read a flat chart
     * @param minAnswerBodyPct smallest |close - open| an answer candle may have, as a
     *                        percentage. Separate from minRangePct because the two guard
     *                        different things: that one keeps the chart readable, this one
     *                        keeps the question answerable — a candle can swing widely and
     *                        still close where it opened, and LONG versus SHORT then comes
     *                        down to the last decimal.
     */
    public record Round(BigDecimal minRangePct, BigDecimal minAnswerBodyPct, int maxAttempts,
                         Duration repeatCacheTtl, int visibleCandles, int guessesPerChart,
                         int revealCandlesAfterComplete, int contextPadding, Timing timing,
                         RateLimit rateLimit) {
    }

    /**
     * The shot clock. {@code seconds} is what the player sees counting down; the server allows
     * {@code grace} on top before it refuses the answer, which absorbs the reveal animation
     * and a slow network rather than punishing them for it.
     *
     * {@code minThinkTime} is the other end: an answer that arrives faster than a person can
     * read a chart and move a hand did not come from a person.
     */
    public record Timing(int seconds, Duration grace, Duration minThinkTime) {
    }

    /** Per signed-in player, or per IP for anonymous play. Fixed one-minute windows. */
    public record RateLimit(int roundsPerMinute, int guessesPerMinute) {
    }

    public record Jwt(String secret, Duration ttl) {
    }
}

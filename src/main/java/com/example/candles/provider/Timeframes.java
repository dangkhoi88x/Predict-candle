package com.example.candles.provider;

import java.time.Duration;
import java.time.Instant;

/**
 * Parsing for the "1h" / "15m" / "1d" strings used throughout the config and the provider
 * API. Lived privately inside BinanceProvider until the ingestion side needed the same
 * arithmetic to work out which candle is still forming.
 */
public final class Timeframes {

    private Timeframes() {
    }

    public static Duration parse(String timeframe) {
        char unit = timeframe.charAt(timeframe.length() - 1);
        long value = Long.parseLong(timeframe.substring(0, timeframe.length() - 1));
        return switch (unit) {
            case 'm' -> Duration.ofMinutes(value);
            case 'h' -> Duration.ofHours(value);
            case 'd' -> Duration.ofDays(value);
            default -> throw new IllegalArgumentException("Unsupported timeframe: " + timeframe);
        };
    }

    /**
     * The open time of the candle that {@code at} falls inside — i.e. the one still forming.
     * Periods are counted from the epoch, which is how exchanges align them.
     */
    public static Instant currentPeriodStart(Instant at, String timeframe) {
        long millis = parse(timeframe).toMillis();
        return Instant.ofEpochMilli(at.toEpochMilli() / millis * millis);
    }
}

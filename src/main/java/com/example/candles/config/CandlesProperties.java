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

    public record Round(BigDecimal minRangePct, int maxAttempts, Duration repeatCacheTtl,
                         int visibleCandles, int guessesPerChart, int revealCandlesAfterComplete) {
    }

    public record Jwt(String secret, Duration ttl) {
    }
}

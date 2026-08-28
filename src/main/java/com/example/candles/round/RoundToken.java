package com.example.candles.round;

/**
 * Stateless session pointer carried inside the signed roundToken JWT: which asset/timeframe,
 * where the visible window starts, and which guess (1-based) within the chart's multi-guess
 * streak this token is for. The server derives the actual answer candle from this at guess
 * time instead of persisting any session.
 */
public record RoundToken(Long assetId, String timeframe, int startIndex, int guessNumber) {
}

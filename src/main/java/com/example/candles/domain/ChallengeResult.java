package com.example.candles.domain;

/**
 * How a finished practice chart went, as the server saw it: the chart and how many of its guesses
 * were right. Signed into the token a practice round's last guess hands back, which is the only
 * way a challenge's advertised score can come into existence — a client that could post its own
 * score could post any score.
 */
public record ChallengeResult(Long assetId, String timeframe, int startIndex, int correct, int total) {
}

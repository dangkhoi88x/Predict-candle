package com.example.candles.api;

import java.time.Instant;
import java.util.List;

public record GuessResponse(
        boolean correct,
        String actualDirection,
        CandleDto actualCandle,
        int guessNumber,
        int totalGuesses,
        boolean sessionComplete,
        String nextRoundToken,
        List<CandleDto> revealCandles,
        RoundIdentity identity,
        RoundContext context
) {

    /**
     * Which stretch of real history the chart was, filled in only once the session is over.
     * During play the client is sent OHLC and nothing else, so this is genuinely new at the
     * end rather than a label that was on screen all along.
     *
     * {@code windowEnd} is the last candle the player actually saw — the final reveal candle,
     * or the last answer candle when history ran out before the reveal did.
     */
    public record RoundIdentity(String asset, String timeframe, Instant windowStart, Instant windowEnd) {
    }

    /**
     * The whole setup drawn out once the guessing is over: the candles that led into the
     * chart, the chart itself, and how it resolved. Sent with the session's last response so
     * the player sees the context here instead of going to look it up somewhere else.
     *
     * The two indexes mark where the played window sits inside {@code candles}, so the
     * client can shade it without recomputing anything: {@code playedFrom} is the first
     * candle the player saw, {@code guessFrom} the first one they had to call.
     */
    public record RoundContext(List<DatedCandleDto> candles, int playedFrom, int guessFrom,
                                int guessCount, List<PatternMark> patterns) {
    }

    /**
     * A candlestick pattern that was on the chart, positioned against {@code RoundContext}'s
     * candle list. Sent only with the finished session — during play it would be a hint about
     * the very candle the player is being asked to call.
     */
    public record PatternMark(String patternId, int startIndex, int length) {
    }
}

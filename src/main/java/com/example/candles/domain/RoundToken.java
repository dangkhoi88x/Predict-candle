package com.example.candles.domain;

import com.example.candles.entity.GuessMode;

/**
 * Stateless session pointer carried inside the signed roundToken JWT: which asset/timeframe,
 * where the visible window starts, and which guess (1-based) within the chart's multi-guess
 * streak this token is for. The server derives the actual answer candle from this at guess
 * time instead of persisting any session.
 *
 * {@code mode} is what keeps the two games apart, and it is a security boundary rather than a
 * label. Without it a player could take the daily challenge's token, spend it against
 * {@code /api/practice/guess} to read the answer at no cost, and then play the daily knowing it
 * — the daily's one-attempt rule counts rows written by the daily endpoint, and a practice row
 * is not one. Each endpoint refuses a token minted for the other.
 *
 * {@code misses} is how many guesses on this chart have been wrong so far, and it drives
 * {@link HintLevel}. It rides in the signed token for the same reason everything else here does:
 * the server keeps no session, and a count the client could edit would be a difficulty dial the
 * client owns.
 */
public record RoundToken(Long assetId, String timeframe, int startIndex, int guessNumber,
                         GuessMode mode, int misses) {
}

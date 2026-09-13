package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Every live round somebody actually called, newest first — the admin's view of a game that
 * stores no rounds.
 *
 * Rounds nobody called are deliberately absent. There is one every hour whether or not a
 * player was looking, so a list of "all recent rounds" would be mostly empty rows with nothing
 * to do about them; a round is on this list exactly when there is something on it to manage.
 */
public record AdminLiveRounds(String asset, String timeframe, Instant generatedAt, List<Round> rounds) {

    /**
     * One round and how it went.
     *
     * {@code settled} is not a stored flag and not a stage in a lifecycle: it is whether a
     * candle exists for this round's open time. That is the same test every other reader of
     * live results makes, {@code SETTLED_LIVE_FLAGS} included, so a round reads as settled here
     * exactly when it counts towards a player's score.
     *
     * Which makes {@code settled == false} on a round whose {@code closeAt} has passed the one
     * row on this page worth chasing: the round is over, the calls are recorded, and no candle
     * ever arrived to decide them. Those calls score nothing and will go on scoring nothing
     * until the gap in candle history is filled.
     *
     * {@code correct} is zero on an unsettled round rather than null. There is a real answer —
     * nobody has been marked right — and a null would have to be drawn as "unknown", which is
     * a different claim.
     */
    public record Round(long number, Instant openTime, Instant closeAt, boolean settled,
                         BigDecimal open, BigDecimal close, String result,
                         long calls, long longCount, long shortCount, long correct) {
    }
}

package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The live round in progress right now: what it opened at, what it is doing this second, and
 * how the crowd has called it so far. `myDirection` is null both for an anonymous viewer and
 * for a signed-in one who has not called this round yet — the two look the same on the wire
 * because the client already knows which case it is in.
 */
public record LiveRoundResponse(
        String asset,
        String timeframe,
        long roundNumber,
        Instant openTime,
        Instant lockAt,
        Instant closeAt,
        boolean locked,
        BigDecimal openPrice,
        BigDecimal livePrice,
        int longCount,
        int shortCount,
        String myDirection,
        List<Participant> participants
) {
    /**
     * One call in the current round's roster, newest first. {@code walletShort} is the same
     * shorthand a display name defaults to — an admin-renamed account ("Raccon") still gets
     * one alongside the name, the way rekto.fun shows both, but never the raw 42-character
     * address: that rule holds here the same way it does for the leaderboard.
     */
    public record Participant(String displayName, String walletShort, String direction, Instant createdAt) {
    }
}

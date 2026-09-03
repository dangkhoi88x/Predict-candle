package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

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
        String myDirection
) {
}

package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One settled round, replayed: what it opened and closed at, who called it right, and the
 * candles either side so the popup can draw the same run-up and aftermath a player would have
 * seen live.
 */
public record LiveRoundDetailResponse(
        long roundNumber,
        Instant openTime,
        Instant closeTime,
        BigDecimal openPrice,
        BigDecimal closePrice,
        String result,
        int longCount,
        int shortCount,
        List<DatedCandleDto> context,
        List<LiveRoundResponse.Participant> participants
) {
}

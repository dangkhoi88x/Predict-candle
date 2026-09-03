package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LiveRoundHistoryResponse(String asset, String timeframe, List<Entry> rounds) {

    /** {@code result} is null for a round still in progress — history only ever lists closed ones. */
    public record Entry(long roundNumber, Instant openTime, Instant closeTime,
                        BigDecimal openPrice, BigDecimal closePrice,
                        String result, int longCount, int shortCount) {
    }
}

package com.example.candles.client;

import java.math.BigDecimal;
import java.time.Instant;

public record CandleData(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
}

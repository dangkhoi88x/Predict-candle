package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.candles.entity.Candle;

/**
 * A candle with its real open time attached. Only used after a session ends, on the context
 * chart — during play the client is sent {@link CandleDto}, which has no timestamp because
 * nothing on screen needs one.
 */
public record DatedCandleDto(Instant time, BigDecimal open, BigDecimal high, BigDecimal low,
                              BigDecimal close) {

    public static DatedCandleDto from(Candle candle) {
        return new DatedCandleDto(candle.getOpenTime(), candle.getOpen(), candle.getHigh(),
                candle.getLow(), candle.getClose());
    }
}

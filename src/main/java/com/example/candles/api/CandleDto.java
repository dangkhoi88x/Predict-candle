package com.example.candles.api;

import com.example.candles.domain.Candle;

import java.math.BigDecimal;

public record CandleDto(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {

    public static CandleDto from(Candle candle) {
        return new CandleDto(candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
    }
}

package com.example.candles.dto.response;

import java.math.BigDecimal;

import com.example.candles.entity.Candle;

public record CandleDto(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {

    public static CandleDto from(Candle candle) {
        return new CandleDto(candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
    }
}

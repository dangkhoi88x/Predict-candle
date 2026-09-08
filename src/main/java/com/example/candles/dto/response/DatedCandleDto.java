package com.example.candles.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.candles.entity.Candle;

/**
 * A candle with its real open time and traded volume attached. Sent to the trading terminal's
 * chart, the live round's history popup, and the game's context chart once a session is over —
 * during play the client is sent {@link CandleDto}, which has neither, because a timestamp is a
 * date to go and look the answer up and volume is a hint the player has not unlocked yet.
 *
 * That second half is why this record can carry volume at all: every response holding one of
 * these is either a chart with nothing left to give away, or a round that has already ended.
 * {@code RoundPlayService} builds its context only when the session is complete, and
 * {@code RoundPlayFlowTest.aRoundStillRunningSendsNoContext} is what keeps that true — sending
 * the context early would hand out the volume hint for nothing.
 */
public record DatedCandleDto(Instant time, BigDecimal open, BigDecimal high, BigDecimal low,
                              BigDecimal close, BigDecimal volume) {

    public static DatedCandleDto from(Candle candle) {
        return new DatedCandleDto(candle.getOpenTime(), candle.getOpen(), candle.getHigh(),
                candle.getLow(), candle.getClose(), candle.getVolume());
    }
}

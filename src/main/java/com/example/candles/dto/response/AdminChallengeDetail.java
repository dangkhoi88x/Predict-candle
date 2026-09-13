package com.example.candles.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * One day's two questions, with the charts behind them.
 *
 * The daily's candles carry their timestamps here, which the player's own copy deliberately
 * never does — for the daily a date *is* the answer, and this is the screen where knowing the
 * date is the entire point. Same reason the answer directions are included: an admin looking at
 * tomorrow's chart is looking to see whether it is a coin flip, and that question cannot be
 * asked without them.
 */
public record AdminChallengeDetail(LocalDate day, long roundNumber, boolean provisional,
                                    Daily daily, Quiz quiz) {

    /**
     * {@code visible} is what the player is shown; {@code answers} are the candles they are
     * asked to call, in order, with the direction each one settled on.
     */
    public record Daily(String asset, String timeframe, Integer startIndex,
                         List<DatedCandleDto> visible, List<Answer> answers, String problem) {
    }

    public record Answer(DatedCandleDto candle, String direction) {
    }

    /** The same slice the player sees, plus which candles inside it are the pattern. */
    public record Quiz(String patternId, String patternName, String asset,
                        List<DatedCandleDto> candles, Integer patternStartIndex,
                        Integer patternLength, List<String> choices, String problem) {
    }
}

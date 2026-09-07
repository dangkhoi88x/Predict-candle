package com.example.candles.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * The day's pattern question, and the caller's answer to it if they have given one.
 *
 * {@code correctPatternId} is null until the player has answered — the whole quiz is that one
 * field, and sending it early would answer the question for them. {@code choices} is safe to
 * send: it is four names in a day-stable order, and which of them is right is exactly what is
 * being withheld.
 *
 * {@code patternStartIndex} and {@code patternLength} say which candles of {@code candles} the
 * question is about. Without them a player would be asked to name a pattern somewhere in a
 * chart, which is a different and much harder question than the one intended.
 *
 * The candles carry no timestamps, like the daily challenge's — a date is something to go and
 * look up rather than read off the chart.
 */
public record PatternQuizResponse(
        LocalDate day,
        long roundNumber,
        String asset,
        String timeframe,
        List<CandleDto> candles,
        int patternStartIndex,
        int patternLength,
        List<String> choices,
        boolean answered,
        String guessedPatternId,
        String correctPatternId,
        boolean correct
) {
}

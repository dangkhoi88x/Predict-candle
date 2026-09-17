package com.example.candles.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import com.example.candles.dto.response.DailyRoundResponse;
import com.example.candles.entity.GuessMode;
import com.example.candles.repository.GuessResultRepository;

/**
 * How hard a chart turned out to be, from the calls other players already made on it.
 *
 * Derived, never stored — the same bargain as streaks, badges and habits. A chart's difficulty is a
 * question asked of {@code guess_results} for those coordinates, so it moves as more people play
 * and cannot drift from what they actually answered.
 *
 * <b>Two rules, and both are about not saying more than the rows support.</b>
 *
 * <ul>
 *   <li><b>Only once the caller has finished.</b> "62% called this one LONG" is most of an answer,
 *       so it travels under exactly the gate the context chart does.</li>
 *   <li><b>Only candles at least {@link #MIN_PLAYERS} people answered.</b> A rate over four players
 *       is four players, not a community, and a chart where no candle clears the floor sends
 *       nothing rather than an empty block that reads as a crowd getting everything wrong.</li>
 * </ul>
 *
 * <b>It does not touch the score.</b> The plan's version of this pays a bonus for a hard chart;
 * that would make yesterday's score move as today's players pile in, and with it a rank the player
 * has already seen. Score stays a fold over the caller's own ordered results; difficulty is shown
 * beside it. Paying for difficulty would mean freezing each round's difficulty at the moment it was
 * played, which is a stored per-guess number — the second source of truth this codebase keeps
 * refusing.
 */
@Service
public class CommunityDifficultyService {

    /**
     * Ten, deliberately below the thirty-per-chart the plan asks for before difficulty could
     * *pay* anything: showing a rate and scoring on one are different promises, and only the
     * second needs a sample stable enough to rank people by.
     */
    public static final int MIN_PLAYERS = 10;

    private final GuessResultRepository guessResults;

    public CommunityDifficultyService(GuessResultRepository guessResults) {
        this.guessResults = guessResults;
    }

    /** Null when no candle of the chart has been answered by enough players. */
    @Transactional(readOnly = true)
    public DailyRoundResponse.Community forChart(GuessMode mode, Long assetId, String timeframe, int startIndex) {
        List<DailyRoundResponse.GuessRate> rates =
                guessResults.communityByGuess(mode, assetId, timeframe, startIndex).stream()
                        .map(row -> new DailyRoundResponse.GuessRate(((Number) row[0]).intValue(),
                                ((Number) row[1]).longValue(), ((Number) row[2]).longValue()))
                        .filter(rate -> rate.players() >= MIN_PLAYERS)
                        .toList();
        return rates.isEmpty() ? null : new DailyRoundResponse.Community(MIN_PLAYERS, rates);
    }
}

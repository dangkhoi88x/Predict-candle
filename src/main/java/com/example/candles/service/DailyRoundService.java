package com.example.candles.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailyRound;
import com.example.candles.domain.PlayStreak;
import com.example.candles.domain.RoundSelection;
import com.example.candles.domain.RoundToken;
import com.example.candles.dto.response.CandleDto;
import com.example.candles.dto.response.DailyRoundResponse;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.exception.InvalidRoundTokenException;
import com.example.candles.repository.GuessResultRepository;

/**
 * The one chart a day, and where a given player stands in it.
 *
 * There is no daily-attempt table. The chart is derived from the date
 * ({@code RoundSelectionService.selectDailyRound}) and the attempt is derived from the guesses
 * already recorded against that chart — so a player who reloads, opens a second tab or comes
 * back on another device sees the same state, and two servers never disagree about whether
 * someone has played. The unique constraint on {@code guess_results} is what makes the second
 * attempt impossible rather than merely discouraged.
 */
@Service
public class DailyRoundService {

    private final RoundSelectionService rounds;
    private final RoundTokenService tokens;
    private final GuessResultRepository guessResults;
    private final CandlesProperties properties;
    private final RoundTimingPolicy timingPolicy;
    private final Clock clock;

    public DailyRoundService(RoundSelectionService rounds,
                             RoundTokenService tokens,
                             GuessResultRepository guessResults,
                             CandlesProperties properties,
                             RoundTimingPolicy timingPolicy,
                             Clock clock) {
        this.rounds = rounds;
        this.tokens = tokens;
        this.guessResults = guessResults;
        this.properties = properties;
        this.timingPolicy = timingPolicy;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** {@code userId} is null for a signed-out visitor, who has no recorded state to read. */
    @Transactional(readOnly = true)
    public DailyRoundResponse round(Long userId) {
        LocalDate day = today();
        RoundSelection selection = rounds.selectDailyRound(day);
        int totalGuesses = properties.round().guessesPerChart();

        List<GuessResult> played = userId == null ? List.of() : guessResults.findRoundGuesses(
                userId, GuessMode.DAILY, selection.asset().getId(),
                selection.timeframe(), selection.startIndex());

        int guessesMade = played.size();
        boolean completed = guessesMade >= totalGuesses;

        // Mid-session gets a token for the guess they are actually on, so closing the tab
        // halfway through loses nothing — the chart is the same chart when they come back.
        String token = completed ? null : tokens.generate(new RoundToken(
                selection.asset().getId(), selection.timeframe(), selection.startIndex(),
                guessesMade + 1, GuessMode.DAILY));

        // Only once it is over: these are the answers, and handing them to a player who still
        // has guesses left would be handing them the answers.
        List<CandleDto> resolved = completed
                ? rounds.answerCandles(selection.asset(), selection.timeframe(),
                        selection.startIndex(), totalGuesses).stream().map(CandleDto::from).toList()
                : List.of();

        return new DailyRoundResponse(
                day,
                DailyRound.forDay(day).number(),
                selection.asset().getSymbol(),
                selection.timeframe(),
                selection.visibleCandles().stream().map(CandleDto::from).toList(),
                totalGuesses,
                timingPolicy.guessSeconds(),
                token,
                guessesMade,
                completed,
                played.stream().map(g -> new DailyRoundResponse.Answer(
                        g.getGuessNumber(),
                        g.getGuessedDirection() == null ? null : g.getGuessedDirection().name(),
                        g.getActualDirection().name(),
                        g.isCorrect())).toList(),
                resolved,
                userId == null ? null : streakFor(userId),
                day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * Refuses a token that is not for today's daily round.
     *
     * Yesterday's token stays cryptographically valid for its whole TTL, and a token minted
     * just before midnight is for a chart that is no longer today's — playing it would write a
     * row that the next day's read cannot see, spending an attempt on nothing.
     */
    public void checkIsToday(RoundToken token) {
        if (token.mode() != GuessMode.DAILY) {
            throw new InvalidRoundTokenException("Không phải lượt chơi hằng ngày.");
        }
        RoundSelection selection = rounds.selectDailyRound(today());
        if (!selection.asset().getId().equals(token.assetId())
                || !selection.timeframe().equals(token.timeframe())
                || selection.startIndex() != token.startIndex()) {
            throw new InvalidRoundTokenException("Lượt chơi hằng ngày đã sang ngày mới.");
        }
    }

    private DailyRoundResponse.Streak streakFor(Long userId) {
        PlayStreak streak = PlayStreak.of(guessResults.distinctDailyDaysDesc(userId), today());
        return new DailyRoundResponse.Streak(streak.current(), streak.best(), streak.daysPlayed());
    }
}

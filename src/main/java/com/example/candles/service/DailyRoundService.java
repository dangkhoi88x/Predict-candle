package com.example.candles.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailyRound;
import com.example.candles.domain.PlayStreak;
import com.example.candles.domain.RoundSelection;
import com.example.candles.domain.RoundToken;
import com.example.candles.dto.response.CandleDto;
import com.example.candles.dto.response.DailyRoundResponse;
import com.example.candles.dto.response.RoundHints;
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

    /**
     * How far back the archive reaches. A cap rather than the whole history since
     * {@link DailyRound#FIRST_DAY}: every entry in the list costs a round selection and a
     * lookup, and a list nobody scrolls to the end of is not worth the queries.
     */
    private static final int MAX_ARCHIVE_DAYS = 60;

    private final RoundSelectionService rounds;
    private final RoundTokenService tokens;
    private final GuessResultRepository guessResults;
    private final CandlesProperties properties;
    private final RoundTimingPolicy timingPolicy;
    private final RoundHintService hintService;
    private final Clock clock;

    public DailyRoundService(RoundSelectionService rounds,
                             RoundTokenService tokens,
                             GuessResultRepository guessResults,
                             CandlesProperties properties,
                             RoundTimingPolicy timingPolicy,
                             RoundHintService hintService,
                             Clock clock) {
        this.rounds = rounds;
        this.tokens = tokens;
        this.guessResults = guessResults;
        this.properties = properties;
        this.timingPolicy = timingPolicy;
        this.hintService = hintService;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** {@code userId} is null for a signed-out visitor, who has no recorded state to read. */
    @Transactional(readOnly = true)
    public DailyRoundResponse round(Long userId) {
        LocalDate day = today();
        return build(userId, day, GuessMode.DAILY,
                userId == null ? null : streakFor(userId),
                day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * A past day, replayed. Recorded under {@link GuessMode#ARCHIVE}, which is what keeps it
     * out of the daily streak — see {@code GuessMode} for why that separation is the whole
     * point of the feature rather than bookkeeping.
     *
     * No streak and no next-round time come back: a replay moves neither.
     */
    @Transactional(readOnly = true)
    public DailyRoundResponse archiveRound(Long userId, LocalDate day) {
        requireArchivable(day);
        return build(userId, day, GuessMode.ARCHIVE, null, null);
    }

    /**
     * The recent past, newest first, with how the caller did on each — the archive list.
     *
     * Today is excluded on purpose: it is not archived yet, and offering it here would be a
     * second door to the same round that writes rows the streak cannot see.
     */
    @Transactional(readOnly = true)
    public List<ArchiveEntry> archive(Long userId, int days) {
        int window = Math.clamp(days, 1, MAX_ARCHIVE_DAYS);
        LocalDate today = today();
        int totalGuesses = properties.round().guessesPerChart();

        List<ArchiveEntry> entries = new ArrayList<>(window);
        for (int back = 1; back <= window; back++) {
            LocalDate day = today.minusDays(back);
            if (day.isBefore(DailyRound.FIRST_DAY)) break;

            RoundSelection selection = rounds.selectDailyRound(day);
            List<GuessResult> played = userId == null ? List.<GuessResult>of()
                    : guessResults.findRoundGuesses(userId, GuessMode.ARCHIVE,
                            selection.asset().getId(), selection.timeframe(), selection.startIndex());

            entries.add(new ArchiveEntry(
                    day,
                    DailyRound.forDay(day).number(),
                    played.size(),
                    totalGuesses,
                    (int) played.stream().filter(GuessResult::isCorrect).count(),
                    played.size() >= totalGuesses));
        }
        return List.copyOf(entries);
    }

    /** One row of the archive list. {@code correct} is only meaningful once {@code completed}. */
    public record ArchiveEntry(LocalDate day, long roundNumber, int guessesMade,
                               int totalGuesses, int correct, boolean completed) {
    }

    /**
     * Shared by today's round and a replayed one. They differ in which mode the recorded rows
     * are read and written under, and in whether a streak and a next-round time apply — not in
     * how a half-finished session is rebuilt, which is the part worth having one copy of.
     */
    private DailyRoundResponse build(Long userId, LocalDate day, GuessMode mode,
                                     DailyRoundResponse.Streak streak, Instant nextRoundAt) {
        RoundSelection selection = rounds.selectDailyRound(day);
        int totalGuesses = properties.round().guessesPerChart();

        List<GuessResult> played = userId == null ? List.of() : guessResults.findRoundGuesses(
                userId, mode, selection.asset().getId(),
                selection.timeframe(), selection.startIndex());

        int guessesMade = played.size();
        boolean completed = guessesMade >= totalGuesses;

        // Rebuilt from the recorded rows rather than carried in the token, because a resumed
        // session may have no token at all — the player closed the tab. The rows are the only
        // thing that survived, so they are what the difficulty has to be derived from.
        int misses = (int) played.stream().filter(g -> !g.isCorrect()).count();

        // Mid-session gets a token for the guess they are actually on, so closing the tab
        // halfway through loses nothing — the chart is the same chart when they come back.
        String token = completed ? null : tokens.generate(new RoundToken(
                selection.asset().getId(), selection.timeframe(), selection.startIndex(),
                guessesMade + 1, mode, misses));

        RoundHints hints = completed ? RoundHints.NONE : hintService.hintsFor(
                selection.asset().getId(), selection.timeframe(), selection.startIndex(),
                guessesMade + 1, misses);

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
                hints,
                streak,
                nextRoundAt);
    }

    /**
     * Refuses a token that is not for today's daily round.
     *
     * Yesterday's token stays cryptographically valid for its whole TTL, and a token minted
     * just before midnight is for a chart that is no longer today's — playing it would write a
     * row that the next day's read cannot see, spending an attempt on nothing.
     */
    /**
     * Refuses a token that is not for the archived day it claims to be for.
     *
     * The day has to come from the request rather than the token, which carries only a chart.
     * Checking it back against that day's chart is what stops an archive token being spent on a
     * different day's round — and, since today is never archivable, what stops today's daily
     * being played through the archive door, where its rows would be invisible to the streak.
     */
    public void checkIsArchived(RoundToken token, LocalDate day) {
        if (token.mode() != GuessMode.ARCHIVE) {
            throw new InvalidRoundTokenException("Không phải lượt chơi lại.");
        }
        requireArchivable(day);
        RoundSelection selection = rounds.selectDailyRound(day);
        if (!selection.asset().getId().equals(token.assetId())
                || !selection.timeframe().equals(token.timeframe())
                || selection.startIndex() != token.startIndex()) {
            throw new InvalidRoundTokenException("Lượt chơi lại không khớp với ngày đã chọn.");
        }
    }

    /**
     * Today is not archived and no day before the first one exists. Both are 400s rather than
     * empty rounds: asking for them is a mistake in the request, not a day with nothing in it.
     */
    private void requireArchivable(LocalDate day) {
        LocalDate today = today();
        if (!day.isBefore(today)) {
            throw new IllegalArgumentException("Chỉ chơi lại được những ngày đã qua.");
        }
        if (day.isBefore(DailyRound.FIRST_DAY) || day.isBefore(today.minusDays(MAX_ARCHIVE_DAYS))) {
            throw new IllegalArgumentException("Ngày này nằm ngoài kho lưu trữ.");
        }
    }

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

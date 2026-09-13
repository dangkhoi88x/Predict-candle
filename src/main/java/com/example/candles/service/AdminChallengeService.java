package com.example.candles.service;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailyRound;
import com.example.candles.domain.RoundSelection;
import com.example.candles.dto.response.AdminChallengeDetail;
import com.example.candles.dto.response.AdminChallenges;
import com.example.candles.dto.response.DatedCandleDto;
import com.example.candles.entity.Candle;
import com.example.candles.entity.ContentKind;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.ContentItemRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.PatternQuizResultRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * What the site will ask, before it asks it.
 *
 * The daily chart and the pattern quiz are both chosen from the date alone and neither stores
 * anything, which is exactly what makes them worth previewing: there is no row to inspect and
 * no midnight job whose log says what tomorrow will be.
 *
 * <h2>The failure this exists to catch</h2>
 *
 * Both selectors can refuse. {@code selectDailyRound} throws when no pair has enough history;
 * {@code PatternQuizService} throws when no pattern in the library has a single unambiguous
 * occurrence anywhere in any asset. Either leaves a whole day of the site broken for everyone
 * until the next midnight, and neither says a word in advance. Here they are called early and
 * their exceptions become a message on a row instead of a 500 in front of a player.
 *
 * <h2>Why a future day is provisional and today is not</h2>
 *
 * A day's chart is drawn from the candles that had closed before *its* midnight. For today that
 * count is frozen, which is what {@code anHourlySyncDoesNotMoveTodaysChart} pins. For a day
 * still to come it is still rising, so the pick will move as the hourly sync lands. A future row
 * is a rehearsal rather than a promise, and it is labelled as one — what it proves is that the
 * day can be built at all.
 *
 * Building a future question is not wasted, though: {@code PatternQuizService} caches a day's
 * question for an hour, so previewing tomorrow warms the same cache the players will hit.
 */
@Service
public class AdminChallengeService {

    /** A working week either side is as far as anyone has a reason to look. */
    private static final int MAX_SPAN = 14;
    private static final int DEFAULT_AHEAD = 3;
    private static final int DEFAULT_BACK = 3;

    /** How many answer candles the preview shows — the ones a player is asked to call. */
    private final int answerCandles;

    private final RoundSelectionService rounds;
    private final PatternQuizService quiz;
    private final CandleRepository candles;
    private final GuessResultRepository guessResults;
    private final PatternQuizResultRepository quizResults;
    private final ContentItemRepository content;
    private final CandlesProperties properties;
    private final Clock clock;

    /**
     * Cached for a minute like the other admin reads, and for a sharper reason than they have:
     * building a day's quiz question scans every asset's history against every pattern in the
     * library, so a refresh button on an uncached list would be an expensive button.
     */
    private final Cache<String, AdminChallenges> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public AdminChallengeService(RoundSelectionService rounds,
                                 PatternQuizService quiz,
                                 CandleRepository candles,
                                 GuessResultRepository guessResults,
                                 PatternQuizResultRepository quizResults,
                                 ContentItemRepository content,
                                 CandlesProperties properties,
                                 Clock clock) {
        this.rounds = rounds;
        this.quiz = quiz;
        this.candles = candles;
        this.guessResults = guessResults;
        this.quizResults = quizResults;
        this.content = content;
        this.properties = properties;
        this.clock = clock;
        this.answerCandles = properties.round().guessesPerChart();
    }

    /** Drops the cached list, so the next read rebuilds every day in it. */
    public void evict() {
        cache.invalidateAll();
    }

    @Transactional(readOnly = true)
    public AdminChallenges list(Integer back, Integer ahead, boolean fresh) {
        int backDays = Math.clamp(back == null ? DEFAULT_BACK : back, 0, MAX_SPAN);
        int aheadDays = Math.clamp(ahead == null ? DEFAULT_AHEAD : ahead, 0, MAX_SPAN);
        String key = backDays + ":" + aheadDays;
        if (fresh) {
            cache.invalidate(key);
        }
        return cache.get(key, k -> build(backDays, aheadDays));
    }

    private AdminChallenges build(int backDays, int aheadDays) {
        LocalDate today = today();
        List<AdminChallenges.Day> days = new ArrayList<>();
        // Newest first: tomorrow is the row somebody opened this page to look at.
        for (int offset = aheadDays; offset >= -backDays; offset--) {
            days.add(day(today.plusDays(offset), today));
        }
        return new AdminChallenges(today, days);
    }

    private AdminChallenges.Day day(LocalDate day, LocalDate today) {
        boolean future = day.isAfter(today);
        boolean isToday = day.equals(today);

        AdminChallenges.Daily daily;
        try {
            RoundSelection selection = rounds.selectDailyRound(day);
            daily = new AdminChallenges.Daily(selection.asset().getSymbol(),
                    selection.timeframe(), selection.startIndex(), null);
        } catch (RuntimeException e) {
            daily = new AdminChallenges.Daily(null, properties.timeframe(), null, message(e));
        }

        return new AdminChallenges.Day(day, DailyRound.forDay(day).number(), future, isToday,
                daily, quizFor(day, future || isToday),
                day.isAfter(today) ? null : participation(day));
    }

    /**
     * A day's pattern, built when it is still to be asked and read back when it is not.
     *
     * Rebuilding a past day's question would scan every asset's whole history against every
     * pattern in the library to arrive at something already written down: every row for a day
     * carries the pattern it asked about, because that is the question rather than the answer.
     * A past day nobody answered has neither, and says so instead of paying for the scan.
     */
    private AdminChallenges.Quiz quizFor(LocalDate day, boolean build) {
        if (!build) {
            List<String> asked = quizResults.askedPatternIds(day, PageRequest.of(0, 1));
            if (asked.isEmpty()) {
                return new AdminChallenges.Quiz(null, null, null, List.of(), "none", null);
            }
            String patternId = asked.getFirst();
            return new AdminChallenges.Quiz(patternId, patternName(patternId), null,
                    List.of(), "answers", null);
        }
        try {
            PatternQuizService.Question question = quiz.questionFor(day);
            return new AdminChallenges.Quiz(question.patternId(), patternName(question.patternId()),
                    question.asset(), question.choices(), "selector", null);
        } catch (RuntimeException e) {
            return new AdminChallenges.Quiz(null, null, null, List.of(), "selector", message(e));
        }
    }

    private AdminChallenges.Participation participation(LocalDate day) {
        Instant since = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Object[] dailyRow = unwrap(guessResults.modeActivityBetween(GuessMode.DAILY, since, until));
        Object[] quizRow = unwrap(quizResults.tallyForDay(day));

        return new AdminChallenges.Participation(asLong(dailyRow[0]), asLong(dailyRow[1]),
                asLong(dailyRow[2]), asLong(quizRow[0]), asLong(quizRow[1]));
    }

    /**
     * One day with both charts.
     *
     * The daily's candles carry their timestamps, which the player's own copy never does — for
     * that game a date is the answer, and this is the one screen where reading the date is the
     * entire point. The answer candles come with it for the same reason: "is tomorrow's chart a
     * coin flip" is not a question that can be asked without them.
     */
    @Transactional(readOnly = true)
    public AdminChallengeDetail detail(LocalDate day) {
        LocalDate today = today();
        boolean provisional = day.isAfter(today);

        AdminChallengeDetail.Daily daily;
        try {
            RoundSelection selection = rounds.selectDailyRound(day);
            int span = properties.round().visibleCandles() + answerCandles;
            List<Candle> window = candles.findWindow(selection.asset().getId(),
                    selection.timeframe(), selection.startIndex(), span);

            List<DatedCandleDto> visible = window.stream()
                    .limit(properties.round().visibleCandles())
                    .map(DatedCandleDto::from)
                    .toList();
            List<AdminChallengeDetail.Answer> answers = window.stream()
                    .skip(properties.round().visibleCandles())
                    .map(c -> new AdminChallengeDetail.Answer(DatedCandleDto.from(c),
                            // The same comparison RoundPlayService scores a guess with.
                            c.getClose().compareTo(c.getOpen()) >= 0
                                    ? Direction.LONG.name() : Direction.SHORT.name()))
                    .toList();

            daily = new AdminChallengeDetail.Daily(selection.asset().getSymbol(),
                    selection.timeframe(), selection.startIndex(), visible, answers, null);
        } catch (RuntimeException e) {
            daily = new AdminChallengeDetail.Daily(null, properties.timeframe(), null,
                    List.of(), List.of(), message(e));
        }

        AdminChallengeDetail.Quiz quizDetail;
        try {
            PatternQuizService.Question question = quiz.questionFor(day);
            quizDetail = new AdminChallengeDetail.Quiz(question.patternId(),
                    patternName(question.patternId()), question.asset(),
                    question.slice().stream().map(DatedCandleDto::from).toList(),
                    question.patternStartIndex(), question.patternLength(),
                    question.choices(), null);
        } catch (RuntimeException e) {
            quizDetail = new AdminChallengeDetail.Quiz(null, null, null, List.of(), null, null,
                    List.of(), message(e));
        }

        return new AdminChallengeDetail(day, DailyRound.forDay(day).number(), provisional,
                daily, quizDetail);
    }

    /**
     * The pattern's own name, from the content library rather than a table in here.
     *
     * A candlestick pattern's key is how its entry finds its matcher, so the two are the same
     * string and the title beside it is the wording an admin edits. Falling back to the id
     * mirrors what {@code CandlePatterns.nameOf} does on the game side, and covers a pattern
     * whose content row has not been written yet.
     */
    private String patternName(String patternId) {
        if (patternId == null) {
            return null;
        }
        return content.findByKindAndItemKey(ContentKind.CANDLE_PATTERN, patternId)
                .map(item -> item.getTitle())
                .orElse(patternId);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** The selector's own words. Both throw with a sentence written for exactly this moment. */
    private static String message(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static Object[] unwrap(Object[] row) {
        if (row.length == 1 && row[0] instanceof Object[] inner) {
            return inner;
        }
        return row;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

}

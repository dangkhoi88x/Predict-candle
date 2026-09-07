package com.example.candles.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailyRound;
import com.example.candles.domain.DailySeed;
import com.example.candles.domain.PatternQuizPick;
import com.example.candles.dto.response.PatternQuizResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.PatternQuizResult;
import com.example.candles.entity.User;
import com.example.candles.pattern.PatternDefinition;
import com.example.candles.pattern.PatternLibrary;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.PatternQuizResultRepository;
import com.example.candles.repository.UserRepository;

/**
 * One pattern to name a day, on a real chart, chosen from the date alone.
 *
 * Reuses the matchers behind the "Mẫu Nến" library rather than inventing a scoring rule, but it
 * cannot reuse {@code PatternExampleService}: that one picks with {@code ThreadLocalRandom} and
 * scans a history that grows every hour. Both are exactly the mistakes {@code selectDailyRound}
 * already had to fix, so the same two fixes apply — seed the pick, and count only the candles
 * that closed before midnight so the corpus cannot move under the day.
 *
 * <h2>The rule the whole feature stands on</h2>
 *
 * A window is only usable if **exactly one** pattern in the library matches at its end. Real
 * charts are full of overlaps — a hammer with a small enough body is also a doji — and marking
 * a player wrong for naming a pattern that genuinely is there would read as a broken quiz and
 * could not be argued with. Rejecting ambiguous windows is what makes the question answerable.
 *
 * If a pattern has no clean occurrence at all, the day walks on to the next pattern in its own
 * seeded order. The date still decides; the data only decides how far down that order it looks.
 */
@Service
public class PatternQuizService {

    /** Enough names to make a guess mean something, few enough to fit a phone. */
    private static final int CHOICE_COUNT = 4;

    /** Context either side of the highlighted candles, so the pattern sits in a real chart. */
    private static final int CONTEXT_BEFORE = 10;
    private static final int CONTEXT_AFTER = 8;

    /** Past this many clean occurrences the pick is varied enough; scanning on costs time. */
    private static final int MAX_MATCHES_COLLECTED = 400;

    private final AssetRepository assets;
    private final CandleRepository candles;
    private final UserRepository users;
    private final PatternQuizResultRepository results;
    private final CandlesProperties properties;
    private final Clock clock;

    /**
     * The day's question, held for an hour. Every player asks for the same one and building it
     * scans a whole asset history; without this the first request of each hour would pay for
     * that and every other request would pay again.
     */
    private final Cache<LocalDate, Question> questionCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public PatternQuizService(AssetRepository assets,
                              CandleRepository candles,
                              UserRepository users,
                              PatternQuizResultRepository results,
                              CandlesProperties properties,
                              Clock clock) {
        this.assets = assets;
        this.candles = candles;
        this.users = users;
        this.results = results;
        this.properties = properties;
        this.clock = clock;
    }

    /** The chart and the four names, with the answer kept back until it has been given. */
    public record Question(String patternId, String asset, String timeframe,
                           List<Candle> slice, int patternStartIndex, int patternLength,
                           List<String> choices) {
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public PatternQuizResponse today(Long userId) {
        LocalDate day = today();
        Question question = questionFor(day);
        PatternQuizResult answered = userId == null ? null
                : results.findByUserIdAndDay(userId, day).orElse(null);

        return response(day, question, answered);
    }

    /**
     * Records an answer, once. A second attempt is not an error — the player gets the same
     * settled result back — so a double-tap or a replayed request cannot cost them the day.
     */
    @Transactional
    public PatternQuizResponse answer(Long userId, String guessedPatternId) {
        LocalDate day = today();
        Question question = questionFor(day);

        if (!question.choices().contains(guessedPatternId)) {
            throw new IllegalArgumentException("Đáp án không nằm trong các lựa chọn của hôm nay.");
        }

        PatternQuizResult existing = results.findByUserIdAndDay(userId, day).orElse(null);
        if (existing == null) {
            User user = users.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("User in session no longer exists"));
            existing = results.save(
                    new PatternQuizResult(user, day, question.patternId(), guessedPatternId));
        }
        return response(day, question, existing);
    }

    private PatternQuizResponse response(LocalDate day, Question question, PatternQuizResult answered) {
        boolean done = answered != null;
        return new PatternQuizResponse(
                day,
                DailyRound.forDay(day).number(),
                question.asset(),
                question.timeframe(),
                question.slice().stream().map(com.example.candles.dto.response.CandleDto::from).toList(),
                question.patternStartIndex(),
                question.patternLength(),
                question.choices(),
                done,
                done ? answered.getGuessedPatternId() : null,
                // The answer is withheld until it has been given, or the quiz answers itself.
                done ? question.patternId() : null,
                done && answered.isCorrect());
    }

    /** Package-private so the tests can walk many days; the cache makes repeats cheap. */
    Question questionFor(LocalDate day) {
        return questionCache.get(day, this::buildQuestion);
    }

    private Question buildQuestion(LocalDate day) {
        Instant midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        String timeframe = properties.timeframe();
        List<Asset> tradable = assets.findAllByOrderByPositionAscSymbolAsc();
        Random random = new Random(DailySeed.forDay(day).value());

        for (String patternId : PatternQuizPick.orderedCandidates(day, List.copyOf(PatternLibrary.all().keySet()))) {
            PatternDefinition definition = PatternLibrary.get(patternId);

            for (Asset asset : tradable) {
                List<Candle> history = historyBefore(asset, timeframe, midnight);
                List<Integer> ends = unambiguousMatchEnds(history, patternId, definition);
                if (ends.isEmpty()) {
                    continue;
                }

                int end = ends.get(random.nextInt(ends.size()));
                int patternStart = end - definition.windowSize();
                int from = Math.max(0, patternStart - CONTEXT_BEFORE);
                int to = Math.min(history.size(), end + CONTEXT_AFTER);

                return new Question(patternId, asset.getSymbol(), timeframe,
                        List.copyOf(history.subList(from, to)),
                        patternStart - from, definition.windowSize(),
                        PatternQuizPick.choicesFor(day, patternId,
                                List.copyOf(PatternLibrary.all().keySet()), CHOICE_COUNT));
            }
        }
        throw new IllegalStateException("No unambiguous pattern example available for " + day);
    }

    /** Only the candles that had closed before the day began, so the corpus cannot drift. */
    private List<Candle> historyBefore(Asset asset, String timeframe, Instant midnight) {
        long total = candles.countByAssetAndTimeframeAndOpenTimeLessThan(asset, timeframe, midnight);
        return total == 0 ? List.of()
                : candles.findWindow(asset.getId(), timeframe, 0, (int) total);
    }

    /**
     * Positions where {@code patternId} matches and nothing else in the library does.
     *
     * "Nothing else" is checked at the same end candle across every window size, not just the
     * asked pattern's own: a three-candle morning star whose last candle is on its own a hammer
     * is a question with two defensible answers, and the player cannot see which one is being
     * asked about.
     */
    private List<Integer> unambiguousMatchEnds(List<Candle> history, String patternId,
                                               PatternDefinition definition) {
        List<Integer> ends = new ArrayList<>();
        for (int end = definition.windowSize(); end <= history.size(); end++) {
            if (!definition.matcher().matches(history.subList(end - definition.windowSize(), end))) {
                continue;
            }
            if (matchesAnythingElse(history, end, patternId)) {
                continue;
            }
            ends.add(end);
            if (ends.size() >= MAX_MATCHES_COLLECTED) {
                break;
            }
        }
        return ends;
    }

    private boolean matchesAnythingElse(List<Candle> history, int end, String patternId) {
        for (Map.Entry<String, PatternDefinition> other : PatternLibrary.all().entrySet()) {
            if (other.getKey().equals(patternId)) {
                continue;
            }
            int size = other.getValue().windowSize();
            if (end - size < 0) {
                continue;
            }
            if (other.getValue().matcher().matches(history.subList(end - size, end))) {
                return true;
            }
        }
        return false;
    }
}

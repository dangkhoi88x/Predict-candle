package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.candles.CandleFixture;
import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.response.PatternQuizResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.pattern.PatternDefinition;
import com.example.candles.pattern.PatternLibrary;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.PatternQuizResultRepository;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pattern quiz, and the property the whole thing rests on: the question must have exactly
 * one defensible answer.
 *
 * Real charts are full of overlapping patterns — a hammer with a small enough body is also a
 * doji — and marking a player wrong for naming a pattern that genuinely is present would read
 * as a broken quiz rather than as a hard one. {@code theHighlightedCandlesMatchExactlyOnePattern}
 * is the test that keeps that honest; the rest is determinism, which is the same lesson the
 * daily round already had to learn.
 */
@SpringBootTest
@Transactional
class PatternQuizTest {

    @Autowired private PatternQuizService quiz;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private UserRepository users;
    @Autowired private PatternQuizResultRepository results;
    @Autowired private CandlesProperties properties;
    @Autowired private StatsService stats;

    /* Every test here asks the quiz a question, and the quiz cannot build one without a
       history to find a clean pattern occurrence in. On CI that table is empty — the pairs
       exist, the Binance backfill never ran — so the corpus is seeded here. A pair that
       already has candles is left alone, which keeps a developer machine asking questions of
       the real market the matchers were tuned on. */
    @BeforeEach
    void seedHistory() {
        String timeframe = properties.timeframe();
        for (Asset asset : assets.findAllByOrderByPositionAscSymbolAsc()) {
            CandleFixture.seedIfEmpty(candles, asset, timeframe);
        }
    }

    private User player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "Q");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    private PatternQuizResponse today() {
        return quiz.today(null);
    }

    @Test
    void everyoneGetsTheSameQuestionAndItNeverShipsTheAnswer() {
        PatternQuizResponse first = today();
        PatternQuizResponse second = quiz.today(player().getId());

        assertThat(first.asset()).isEqualTo(second.asset());
        assertThat(first.patternStartIndex()).isEqualTo(second.patternStartIndex());
        assertThat(first.choices()).isEqualTo(second.choices());
        assertThat(first.choices()).hasSize(4).doesNotHaveDuplicates();

        // Unanswered, the one field that matters must not be there.
        assertThat(first.answered()).isFalse();
        assertThat(first.correctPatternId()).isNull();
        assertThat(first.correct()).isFalse();
    }

    @Test
    void theHighlightedCandlesAreRealAndSitInsideTheChart() {
        PatternQuizResponse round = today();

        assertThat(round.patternLength()).isPositive();
        assertThat(round.patternStartIndex()).isNotNegative();
        assertThat(round.patternStartIndex() + round.patternLength())
                .isLessThanOrEqualTo(round.candles().size());
        // Context either side is what makes it a chart rather than a flashcard.
        assertThat(round.candles().size()).isGreaterThan(round.patternLength());
    }

    /**
     * The load-bearing rule, checked against the library itself rather than the selector's own
     * opinion — and checked over a run of days, not one.
     *
     * One day proves nothing here: most windows are unambiguous anyway, so a single question
     * passes this whether or not the filter exists. Removing the filter and watching one day
     * still pass is exactly what happened the first time this was written. Thirty days is
     * enough that an unfiltered selector reliably trips over an overlap — a hammer whose body
     * is small enough to also be a doji, most often.
     */
    @Test
    void everyDaysQuestionMatchesExactlyOnePattern() {
        LocalDate day = LocalDate.of(2026, 3, 1);

        for (int i = 0; i < 30; i++) {
            PatternQuizService.Question question = quiz.questionFor(day.plusDays(i));
            List<Candle> window = question.slice().subList(
                    question.patternStartIndex(),
                    question.patternStartIndex() + question.patternLength());
            int end = question.patternStartIndex() + question.patternLength();

            int matches = 0;
            String claimed = null;
            for (Map.Entry<String, PatternDefinition> entry : PatternLibrary.all().entrySet()) {
                int size = entry.getValue().windowSize();
                if (end - size < 0) continue;
                if (entry.getValue().matcher().matches(question.slice().subList(end - size, end))) {
                    matches++;
                    claimed = claimed == null ? entry.getKey() : claimed + "+" + entry.getKey();
                }
            }
            assertThat(matches)
                    .as("day %s (%s) claimed by: %s", day.plusDays(i), question.patternId(), claimed)
                    .isEqualTo(1);
            assertThat(window).hasSize(question.patternLength());
        }
    }

    /**
     * The mistake the daily round had to be fixed for. A corpus that grows every hour would
     * move the question under the players who already saw it.
     */
    @Test
    void anHourlySyncDoesNotChangeTodaysQuestion() {
        PatternQuizResponse before = today();

        Asset asset = assets.findBySymbol(before.asset()).orElseThrow();
        List<Candle> last = candles.findWindow(asset.getId(), properties.timeframe(),
                (int) candles.countByAssetAndTimeframe(asset, properties.timeframe()) - 1, 1);
        candles.saveAndFlush(new Candle(asset, properties.timeframe(),
                last.get(0).getOpenTime().plus(Duration.ofHours(1)),
                BigDecimal.valueOf(100), BigDecimal.valueOf(110),
                BigDecimal.valueOf(90), BigDecimal.valueOf(105), BigDecimal.ONE));

        PatternQuizResponse after = today();
        assertThat(after.asset()).isEqualTo(before.asset());
        assertThat(after.patternStartIndex()).isEqualTo(before.patternStartIndex());
        assertThat(after.choices()).isEqualTo(before.choices());
    }

    @Test
    void answeringRecordsItOnceAndThenRepeatsTheSettledResult() {
        User user = player();
        PatternQuizResponse question = today();
        String pick = question.choices().get(0);

        PatternQuizResponse answered = quiz.answer(user.getId(), pick);
        assertThat(answered.answered()).isTrue();
        assertThat(answered.guessedPatternId()).isEqualTo(pick);
        // Now that it is settled the answer comes back, and only now.
        assertThat(answered.correctPatternId()).isNotBlank();
        assertThat(answered.correct()).isEqualTo(pick.equals(answered.correctPatternId()));

        // A second answer must not overwrite the first — a double tap cannot cost the day.
        String other = question.choices().stream().filter(c -> !c.equals(pick)).findFirst().orElseThrow();
        PatternQuizResponse again = quiz.answer(user.getId(), other);
        assertThat(again.guessedPatternId()).isEqualTo(pick);
        assertThat(results.findByUserIdAndDay(user.getId(), quiz.today())).isPresent();
    }

    @Test
    void anAnswerOutsideTodaysChoicesIsRefused() {
        User user = player();
        assertThatThrownBy(() -> quiz.answer(user.getId(), "not-a-pattern"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(results.findByUserIdAndDay(user.getId(), quiz.today())).isEmpty();
    }

    /**
     * The one system the quiz is wired into. It is invisible to score, the leaderboard,
     * retention and badges on purpose — naming a pattern and calling a direction are not the
     * same currency — but answering it is turning up, which is all the day streak measures.
     */
    @Test
    void answeringTheQuizKeepsTheDayStreakAliveButEarnsNoScore() {
        User user = player();
        assertThat(stats.forUser(user.getId()).dayStreak().current()).isZero();

        quiz.answer(user.getId(), today().choices().get(0));

        var after = stats.forUser(user.getId());
        assertThat(after.dayStreak().current()).isEqualTo(1);
        assertThat(after.dayStreak().playedToday()).isTrue();
        // And nothing else moved: the quiz is not a guess.
        assertThat(after.recorded().total()).isZero();
        assertThat(after.recorded().score()).isZero();
    }
}

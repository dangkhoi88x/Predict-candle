package com.example.candles.service;

import com.example.candles.CandleFixture;
import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.response.AdminChallengeDetail;
import com.example.candles.dto.response.AdminChallenges;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.PatternQuizResult;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.PatternQuizResultRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The day-by-day preview of what the site is about to ask.
 *
 * Both questions are functions of the date and neither stores anything, so there is nothing to
 * inspect and no job whose log says what tomorrow will be. What this page is really for is the
 * failure the two selectors share — a day neither of them can build — and the tests here are
 * mostly about that, plus the labelling that keeps a rehearsal from reading as a promise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminChallengeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminChallengeService challenges;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private UserRepository users;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private PatternQuizResultRepository quizResults;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;

    /* Both selectors need a history to pick from. On CI the table is empty — the pairs exist,
       the Binance backfill never ran — so this seeds one, and leaves a developer machine
       testing against the real market. */
    @BeforeEach
    void seedHistory() {
        challenges.evict();
        for (Asset asset : assets.findAllByOrderByPositionAscSymbolAsc()) {
            CandleFixture.seedIfEmpty(candles, asset, properties.timeframe());
        }
    }

    private User user(Role role) {
        User u = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "T");
        u.assignRole(role);
        return users.saveAndFlush(u);
    }

    private AdminChallenges.Day dayIn(AdminChallenges list, LocalDate day) {
        return list.days().stream().filter(d -> d.day().equals(day)).findFirst()
                .orElseThrow(() -> new AssertionError(day + " not in the list"));
    }

    @Test
    void theListRunsFromTomorrowBackwardsAndKnowsWhichDayIsToday() {
        AdminChallenges list = challenges.list(2, 2, true);

        assertThat(list.days()).hasSize(5);
        // Newest first: tomorrow is the row somebody opened this page to look at.
        assertThat(list.days().getFirst().day()).isEqualTo(list.today().plusDays(2));
        assertThat(list.days().getLast().day()).isEqualTo(list.today().minusDays(2));
        assertThat(list.days()).filteredOn(AdminChallenges.Day::isToday).hasSize(1);
        assertThat(dayIn(list, list.today()).roundNumber())
                .isEqualTo(dayIn(list, list.today().minusDays(1)).roundNumber() + 1);
    }

    /**
     * A day still to come is a rehearsal, not a promise. Its chart is drawn from the candles
     * that will have closed before *its* midnight, and that count is still rising — the same
     * property {@code anHourlySyncDoesNotMoveTodaysChart} pins from the other side, where
     * today's count is frozen and does not move.
     */
    @Test
    void futureDaysAreLabelledProvisionalAndTodayIsNot() {
        AdminChallenges list = challenges.list(1, 1, true);

        assertThat(dayIn(list, list.today().plusDays(1)).provisional()).isTrue();
        assertThat(dayIn(list, list.today()).provisional()).isFalse();
        assertThat(dayIn(list, list.today().minusDays(1)).provisional()).isFalse();
    }

    /**
     * The whole point. Both selectors throw rather than return nothing, and a throw reaching an
     * admin page as a 500 would say only that something is broken — not which day, and not that
     * the day in question is tomorrow.
     */
    @Test
    void aDayThatCannotBeBuiltIsAMessageOnItsRowRatherThanAFailedRequest() {
        // Round #1 is 2026-01-01; the day before it predates every candle the fixture holds,
        // so the daily selector has nothing to pick from and says so.
        LocalDate impossible = LocalDate.of(2020, 1, 1);

        AdminChallengeDetail detail = challenges.detail(impossible);

        assertThat(detail.daily().problem()).isNotBlank();
        assertThat(detail.daily().asset()).isNull();
        assertThat(detail.daily().visible()).isEmpty();
        // The other half of the day is reported on its own: one selector failing is not both.
        assertThat(detail.quiz().problem()).isNotBlank();
    }

    /**
     * The daily's candles carry timestamps here, which the player's copy never does — a date is
     * the answer to that game — and the answer candles come with them, because "is tomorrow a
     * coin flip" cannot be asked without them.
     */
    @Test
    void theDetailCarriesTheWindowItsDatesAndTheAnswers() {
        AdminChallengeDetail detail = challenges.detail(challenges.list(0, 0, true).today());

        assertThat(detail.daily().problem()).isNull();
        assertThat(detail.daily().visible()).hasSize(properties.round().visibleCandles());
        assertThat(detail.daily().visible()).allSatisfy(c -> assertThat(c.time()).isNotNull());
        assertThat(detail.daily().answers()).hasSize(properties.round().guessesPerChart());
        assertThat(detail.daily().answers()).allSatisfy(a -> {
            assertThat(a.direction()).isIn("LONG", "SHORT");
            // The same comparison RoundPlayService scores a guess with.
            boolean up = a.candle().close().compareTo(a.candle().open()) >= 0;
            assertThat(a.direction()).isEqualTo(up ? "LONG" : "SHORT");
        });

        assertThat(detail.quiz().problem()).isNull();
        assertThat(detail.quiz().choices()).hasSize(4).contains(detail.quiz().patternId());
        assertThat(detail.quiz().patternLength()).isPositive();
        assertThat(detail.quiz().candles()).isNotEmpty();
    }

    /**
     * A past day's pattern is read off an answer somebody gave rather than rebuilt. Every row
     * for a day carries the pattern it asked about — that is the question, not the answer — and
     * rebuilding means scanning every asset's history against every pattern in the library for
     * a day already over.
     */
    @Test
    void aPastDayReadsItsPatternBackFromTheAnswersInsteadOfRebuildingIt() {
        LocalDate yesterday = challenges.list(0, 0, true).today().minusDays(1);
        quizResults.saveAndFlush(new PatternQuizResult(user(Role.USER), yesterday, "hammer", "doji"));
        challenges.evict();

        AdminChallenges.Quiz quiz = dayIn(challenges.list(2, 0, true), yesterday).quiz();

        assertThat(quiz.patternId()).isEqualTo("hammer");
        assertThat(quiz.resolved()).isEqualTo("answers");
        // Nobody was asked about it here, so there is no chart or choice list to report.
        assertThat(quiz.choices()).isEmpty();
    }

    /**
     * The two games are counted apart because they are stored apart, and an archive replay is
     * kept out of the daily's figures — it lands on the day it was played, not the day it was
     * set, and counting it would credit today with attempts at somebody else's chart.
     */
    @Test
    void participationCountsTheTwoGamesApartAndIgnoresArchiveReplays() {
        LocalDate today = challenges.list(0, 0, true).today();
        /* Asserted as a delta rather than as absolutes. A developer database holds whatever
           the app was last played on, and today is exactly the day it was played on — so
           "two guesses today" is not a fact a test can state, while "two more than before"
           is, and it is the same claim. */
        AdminChallenges.Participation before = dayIn(challenges.list(0, 0, true), today).participation();

        User player = user(Role.USER);
        Asset asset = assets.findAllByOrderByPositionAscSymbolAsc().getFirst();
        guessResults.saveAll(java.util.List.of(
                new GuessResult(player, asset, "1h", 5001, 1, Direction.LONG, Direction.LONG, GuessMode.DAILY),
                new GuessResult(player, asset, "1h", 5002, 1, Direction.SHORT, Direction.LONG, GuessMode.DAILY),
                // A replay of an older round, played today. Not today's challenge.
                new GuessResult(player, asset, "1h", 5003, 1, Direction.LONG, Direction.LONG, GuessMode.ARCHIVE)));
        guessResults.flush();
        quizResults.saveAndFlush(new PatternQuizResult(player, today, "hammer", "hammer"));
        challenges.evict();

        AdminChallenges.Participation after = dayIn(challenges.list(0, 0, true), today).participation();

        // Two DAILY rows and one ARCHIVE row went in; only the two are the daily challenge's.
        assertThat(after.dailyGuesses() - before.dailyGuesses()).isEqualTo(2);
        assertThat(after.dailyPlayers() - before.dailyPlayers()).isEqualTo(1);
        assertThat(after.dailyCorrect() - before.dailyCorrect()).isEqualTo(1);
        assertThat(after.quizAnswers() - before.quizAnswers()).isEqualTo(1);
        assertThat(after.quizCorrect() - before.quizCorrect()).isEqualTo(1);
    }

    @Test
    void bothRoutesAreClosedToEveryoneButAdmins() throws Exception {
        mockMvc.perform(get("/api/admin/challenges")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/challenges")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(user(Role.USER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/challenges?back=0&ahead=1")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(user(Role.ADMIN))))
                .andExpect(status().isOk());
    }
}

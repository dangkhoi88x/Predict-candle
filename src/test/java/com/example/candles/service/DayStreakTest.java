package com.example.candles.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.candles.dto.response.StatsResponse;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day streak against the real database, which is where the parts a pure
 * {@link com.example.candles.domain.PlayStreak} test cannot reach live: the union across
 * practice and live rows, the UTC day cast, and the {@code java.sql.Date} the driver hands
 * back for it.
 *
 * Rows are backdated with an update, since {@link GuessResult} stamps its own creation time
 * and nothing in the application ever writes a result into the past.
 */
@SpringBootTest
@Transactional
class DayStreakTest {

    @Autowired private StatsService statsService;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private EntityManager entityManager;

    private User player() {
        return users.saveAndFlush(new User("0x" + UUID.randomUUID().toString().replace("-", ""), "S"));
    }

    /** One recorded guess, dated {@code daysAgo} days before now. */
    private void guessedDaysAgo(User user, int startIndex, int daysAgo) {
        Asset asset = assets.findAll().getFirst();
        GuessResult saved = guessResults.saveAndFlush(
                new GuessResult(user, asset, "1h", startIndex, 1, Direction.LONG, Direction.LONG, GuessMode.PRACTICE));
        entityManager.createNativeQuery("update guess_results set created_at = :at where id = :id")
                .setParameter("at", Instant.now().minus(daysAgo, ChronoUnit.DAYS))
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    void anAccountThatHasNeverPlayedReadsZero() {
        StatsResponse.DayStreak streak = statsService.forUser(player().getId()).dayStreak();

        assertThat(streak.current()).isZero();
        assertThat(streak.best()).isZero();
        assertThat(streak.daysPlayed()).isZero();
        assertThat(streak.playedToday()).isFalse();
    }

    @Test
    void severalGuessesOnOneDayAreStillOneDay() {
        User user = player();
        guessedDaysAgo(user, 1, 0);
        guessedDaysAgo(user, 2, 0);
        guessedDaysAgo(user, 3, 0);

        StatsResponse.DayStreak streak = statsService.forUser(user.getId()).dayStreak();

        assertThat(streak.current()).isEqualTo(1);
        assertThat(streak.daysPlayed()).isEqualTo(1);
        assertThat(streak.playedToday()).isTrue();
    }

    @Test
    void consecutiveDaysBuildTheStreakAndAGapBreaksIt() {
        User user = player();
        List.of(1, 2, 3).forEach(daysAgo -> guessedDaysAgo(user, daysAgo, daysAgo));
        guessedDaysAgo(user, 9, 9);

        StatsResponse.DayStreak streak = statsService.forUser(user.getId()).dayStreak();

        // Yesterday, the day before and the day before that — still running, since a day the
        // player has not reached yet is not a day they missed.
        assertThat(streak.current()).isEqualTo(3);
        assertThat(streak.best()).isEqualTo(3);
        assertThat(streak.daysPlayed()).isEqualTo(4);
        assertThat(streak.playedToday()).isFalse();
    }

    /**
     * Badges are derived, not awarded, so the guarantee worth pinning is that they follow the
     * recorded history rather than a row someone remembered to write. A player who has just
     * played has the entry-level badge; one who has never played has none, and still sees the
     * whole catalogue to aim at.
     */
    @Test
    void badgesFollowTheRecordedHistoryWithoutBeingAwarded() {
        User fresh = player();
        StatsResponse before = statsService.forUser(fresh.getId());

        assertThat(before.achievements()).isNotEmpty();
        assertThat(before.achievements()).allMatch(b -> !b.earned());

        // Five guesses is one full chart, which is what the entry badge asks for.
        for (int i = 1; i <= 5; i++) guessedDaysAgo(fresh, i, 0);

        StatsResponse after = statsService.forUser(fresh.getId());
        assertThat(after.achievements()).hasSameSizeAs(before.achievements());
        assertThat(after.achievements())
                .filteredOn(b -> b.id().equals("first-chart"))
                .allMatch(StatsResponse.Badge::earned);
        // Nothing else came with it — thresholds must not leak into each other.
        assertThat(after.achievements())
                .filteredOn(b -> b.id().equals("hundred-guesses"))
                .allMatch(b -> !b.earned() && b.progress() == 5);
    }

    @Test
    void playingOnlyLongAgoLeavesNothingRunning() {
        User user = player();
        guessedDaysAgo(user, 1, 30);
        guessedDaysAgo(user, 2, 31);

        StatsResponse.DayStreak streak = statsService.forUser(user.getId()).dayStreak();

        assertThat(streak.current()).isZero();
        assertThat(streak.best()).isEqualTo(2);
    }
}
